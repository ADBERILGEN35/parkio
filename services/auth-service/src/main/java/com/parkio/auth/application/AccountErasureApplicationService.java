package com.parkio.auth.application;

import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.DurableErasurePutResult;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.application.port.DurableErasureRecordStore;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.result.AccountDeletionStatusView;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.auth.domain.event.UserErasureRequestedEvent;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.metrics.ErasureMetrics;
import com.parkio.auth.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import com.parkio.auth.infrastructure.persistence.entity.ErasureRequestEntity;
import com.parkio.auth.infrastructure.persistence.entity.ErasureServiceAckEntity;
import com.parkio.auth.infrastructure.persistence.ErasureDurableWorkerRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureRequestJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureServiceAckJpaRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountErasureApplicationService {

    public static final List<String> DEFAULT_PARTICIPANTS = List.of(
            "user",
            "parking",
            "media",
            "moderation",
            "gamification",
            "notification",
            "analytics",
            "ai-validation");

    private static final Logger log = LoggerFactory.getLogger(AccountErasureApplicationService.class);
    private static final Duration DEFAULT_WORKER_BASE_BACKOFF = Duration.ofSeconds(5);
    private static final Duration DEFAULT_WORKER_MAX_BACKOFF = Duration.ofMinutes(15);
    private static final Instant DURABLE_RETRY_EXHAUSTED_NEXT =
            Instant.parse("9999-12-31T23:59:59Z");

    private final AuthUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetRepository passwordResets;
    private final PasswordHasher passwordHasher;
    private final OutboxEventAppender outbox;
    private final InboxEventRepository inbox;
    private final ErasureRequestJpaRepository requests;
    private final ErasureServiceAckJpaRepository acks;
    private final ErasedUserTombstoneJpaRepository tombstones;
    private final ErasureMetrics metrics;
    private final Clock clock;
    private final boolean enabled;
    private final Set<String> participants;
    private final boolean durableRecordingEnabled;
    private final DurableErasureRecordStore durableStore;
    private final ErasureDurableWorkerRepository workerRepository;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate withoutTransaction;

    public AccountErasureApplicationService(
            AuthUserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordResetRepository passwordResets,
            PasswordHasher passwordHasher,
            OutboxEventAppender outbox,
            InboxEventRepository inbox,
            ErasureRequestJpaRepository requests,
            ErasureServiceAckJpaRepository acks,
            ErasedUserTombstoneJpaRepository tombstones,
            ErasureMetrics metrics,
            Clock clock,
            boolean enabled,
            String participantsCsv) {
        this(users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, metrics, clock, enabled, participantsCsv,
                false, (DurableErasureRecordStore) null, null, (PlatformTransactionManager) null);
    }

    public AccountErasureApplicationService(
            AuthUserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordResetRepository passwordResets,
            PasswordHasher passwordHasher,
            OutboxEventAppender outbox,
            InboxEventRepository inbox,
            ErasureRequestJpaRepository requests,
            ErasureServiceAckJpaRepository acks,
            ErasedUserTombstoneJpaRepository tombstones,
            ErasureMetrics metrics,
            Clock clock,
            boolean enabled,
            String participantsCsv,
            boolean durableRecordingEnabled,
            DurableErasureRecordStore durableStore,
            ErasureDurableWorkerRepository workerRepository,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordResets = passwordResets;
        this.passwordHasher = passwordHasher;
        this.outbox = outbox;
        this.inbox = inbox;
        this.requests = requests;
        this.acks = acks;
        this.tombstones = tombstones;
        this.metrics = metrics;
        this.clock = clock;
        this.enabled = enabled;
        this.participants = Arrays.stream(participantsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.durableRecordingEnabled = durableRecordingEnabled;
        this.durableStore = durableStore;
        this.workerRepository = workerRepository;
        if (transactionManager == null) {
            this.requiresNew = null;
            this.withoutTransaction = null;
        } else {
            TransactionTemplate newTx = new TransactionTemplate(transactionManager);
            newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNew = newTx;
            TransactionTemplate suspended = new TransactionTemplate(transactionManager);
            suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            this.withoutTransaction = suspended;
        }
    }

    @Autowired
    public AccountErasureApplicationService(
            AuthUserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordResetRepository passwordResets,
            PasswordHasher passwordHasher,
            OutboxEventAppender outbox,
            InboxEventRepository inbox,
            ErasureRequestJpaRepository requests,
            ErasureServiceAckJpaRepository acks,
            ErasedUserTombstoneJpaRepository tombstones,
            ErasureMetrics metrics,
            Clock clock,
            @Value("${parkio.privacy.account-erasure.enabled:false}") boolean enabled,
            @Value("${parkio.privacy.account-erasure.participants:user,parking,media,moderation,gamification,notification,analytics,ai-validation}")
                    String participantsCsv,
            @Value("${parkio.privacy.account-erasure.durable-recording-enabled:false}") boolean durableRecordingEnabled,
            ObjectProvider<DurableErasureRecordStore> durableStores,
            ObjectProvider<ErasureDurableWorkerRepository> workerRepositories,
            ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this(users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, metrics, clock, enabled, participantsCsv,
                durableRecordingEnabled, durableStores.getIfAvailable(),
                workerRepositories.getIfAvailable(), transactionManagers.getIfAvailable());
    }

    @Transactional
    public AccountDeletionStatusView requestDeletion(UUID principalUserId, String password) {
        if (!enabled) {
            throw new AuthException(AuthErrorCode.ACCOUNT_ERASURE_DISABLED);
        }
        if (durableRecordingEnabled && durableStore == null) {
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        }
        AuthUser user = users.findById(principalUserId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
        if (!passwordHasher.matches(password, user.passwordHash())) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        Instant now = clock.instant();
        var existing = requests.findFirstByAuthUserIdOrderByRequestedAtDesc(principalUserId);
        if (existing.isPresent()) {
            String status = existing.get().getStatus();
            if ("COMPLETE".equals(status) || "IN_PROGRESS".equals(status) || "REQUESTED".equals(status)
                    || "FAILED_RETRYING".equals(status)) {
                if (durableRecordingEnabled && "PENDING_DURABLE".equals(existing.get().getDurableRecordingStatus())) {
                    schedulePersistAfterCommit(existing.get().getId());
                }
                return new AccountDeletionStatusView(existing.get().getId(), publicStatus(existing.get()));
            }
        }
        if (!user.beginErasure(now)) {
            throw new AuthException(AuthErrorCode.ACCOUNT_ERASURE_IN_PROGRESS);
        }
        users.save(user);
        refreshTokens.revokeAllActiveForUser(user.id(), RefreshTokenRevocationReason.ACCOUNT_ERASURE, now);
        passwordResets.consumeActiveForUser(user.id(), now);
        tombstones.save(new ErasedUserTombstoneEntity(user.id(), now));
        UUID requestId = UUID.randomUUID();
        ErasureRequestEntity request = new ErasureRequestEntity(requestId, user.id(), "IN_PROGRESS", now);
        if (durableRecordingEnabled) {
            request.markPendingDurable();
        }
        requests.save(request);
        outbox.append(UserErasureRequestedEvent.of(requestId, user.id(), now));
        metrics.requested();
        log.info("erasure requested requestId={} service=auth status=IN_PROGRESS", requestId);
        if (durableRecordingEnabled) {
            schedulePersistAfterCommit(requestId);
        }
        return new AccountDeletionStatusView(requestId, "IN_PROGRESS");
    }

    @Transactional(readOnly = true)
    public AccountDeletionStatusView status(UUID principalUserId) {
        if (!enabled) {
            throw new AuthException(AuthErrorCode.ACCOUNT_ERASURE_DISABLED);
        }
        return requests.findFirstByAuthUserIdOrderByRequestedAtDesc(principalUserId)
                .map(row -> new AccountDeletionStatusView(row.getId(), publicStatus(row)))
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
    }

    @Transactional
    public void handleAcknowledgement(UserErasureAcknowledgedEvent event) {
        if (!inbox.tryClaim(event.eventId(), UserErasureAcknowledgedEvent.TYPE, clock.instant())) {
            return;
        }
        ErasureRequestEntity request = requests.findById(event.erasureRequestId()).orElse(null);
        if (request == null) {
            return;
        }
        String service = event.serviceName() == null ? "" : event.serviceName().trim().toLowerCase(Locale.ROOT);
        if (!participants.contains(service)) {
            log.info("erasure ack ignored requestId={} service={} status=unknown-participant",
                    event.erasureRequestId(), service);
            return;
        }
        acks.save(new ErasureServiceAckEntity(
                event.erasureRequestId(), service, event.status(), clock.instant()));
        if ("FAILED".equalsIgnoreCase(event.status())) {
            request.markFailedRetrying("PARTICIPANT_FAILED");
            requests.save(request);
            metrics.failed();
            log.info("erasure failed requestId={} service={} status=FAILED_RETRYING",
                    event.erasureRequestId(), service);
            return;
        }
        long success = acks.countByErasureRequestIdAndStatus(event.erasureRequestId(), "SUCCESS");
        if (success >= participants.size()) {
            completeLocal(request);
        }
    }

    public void persistDurableRecord(UUID requestId) {
        if (!durableRecordingEnabled) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            schedulePersistAfterCommit(requestId);
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AuthException(
                    AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable persist cannot run inside an open database transaction");
        }
        persistDurableRecordNow(requestId);
    }

    @Transactional
    public int replayTombstones() {
        int replayed = 0;
        Instant now = clock.instant();
        for (ErasedUserTombstoneEntity tombstone : tombstones.findAll()) {
            AuthUser user = users.findById(tombstone.getAuthUserId()).orElse(null);
            if (user == null) {
                continue;
            }
            if (user.status().canAuthenticate()) {
                user.beginErasure(now);
                users.save(user);
                refreshTokens.revokeAllActiveForUser(user.id(), RefreshTokenRevocationReason.ACCOUNT_ERASURE, now);
                var existing = requests.findFirstByAuthUserIdOrderByRequestedAtDesc(user.id());
                UUID requestId = existing.map(ErasureRequestEntity::getId).orElseGet(UUID::randomUUID);
                if (existing.isEmpty()) {
                    ErasureRequestEntity created = new ErasureRequestEntity(requestId, user.id(), "IN_PROGRESS", now);
                    if (durableRecordingEnabled) {
                        created.markPendingDurable();
                    }
                    requests.save(created);
                    if (durableRecordingEnabled) {
                        schedulePersistAfterCommit(requestId);
                    }
                } else if ("COMPLETE".equals(existing.get().getStatus())) {
                    completeLocal(existing.get());
                    replayed++;
                    continue;
                }
                outbox.append(UserErasureRequestedEvent.of(requestId, user.id(), now));
                replayed++;
                log.info("erasure replay requestId={} service=auth status=IN_PROGRESS", requestId);
            } else if (user.status().name().equals("ERASURE_IN_PROGRESS")) {
                completeLocalIfAcksPresent(user.id());
            }
        }
        return replayed;
    }

    @Transactional(readOnly = true)
    public long stuckCount(Duration sla) {
        return requests.countStuckBefore(clock.instant().minus(sla));
    }

    private void putDurable(DurableErasureRecord candidate) {
        DurableErasurePutResult result = durableStore.putIfAbsent(candidate);
        if (result.conflict()) {
            throw new AuthException(AuthErrorCode.CONFLICT, "ambiguous durable recording retry");
        }
    }

    /**
     * Worker entry: persist or reconcile a claimed row. Store I/O is outside any database
     * transaction; stale claims are ignored via the lease token.
     */
    public void processWorkerPersistClaim(
            ErasureDurableWorkerClaim claim, int maxAttempts, Duration baseBackoff, Duration maxBackoff) {
        if (workerRepository == null || durableStore == null || !durableRecordingEnabled) {
            return;
        }
        try {
            DurableErasureRecord candidate = DurableErasureRecord.of(
                    claim.requestId(), claim.authUserId(), claim.requestedAt());
            var existing = durableStore.findByRequestId(claim.requestId());
            if (existing.isPresent()) {
                if (!existing.get().bodyDigest().equals(candidate.bodyDigest())) {
                    scheduleWorkerRetry(
                            claim, maxAttempts, baseBackoff, maxBackoff, "DURABLE_RECORDING_CONFLICT");
                    return;
                }
            } else {
                if (withoutTransaction != null) {
                    withoutTransaction.executeWithoutResult(status -> putDurable(candidate));
                } else {
                    putDurable(candidate);
                }
            }
            int marked = workerRepository.markDurablyRecordedIfClaimed(
                    claim.requestId(), claim.claimToken(), clock.instant());
            if (marked == 0) {
                return;
            }
            tryCompleteIfReady(claim.requestId());
        } catch (RuntimeException ex) {
            log.warn("durable persist worker failed requestId={}", claim.requestId(), ex);
            scheduleWorkerRetry(claim, maxAttempts, baseBackoff, maxBackoff, "DURABLE_PERSIST_FAILED");
        }
    }

    public void processWorkerReconcileClaim(ErasureDurableWorkerClaim claim) {
        if (workerRepository == null || !durableRecordingEnabled || requiresNew == null) {
            return;
        }
        requiresNew.executeWithoutResult(status -> {
            ErasureRequestEntity request = requests.findById(claim.requestId()).orElse(null);
            if (request == null || !claimStillHeld(request, claim)) {
                return;
            }
            if ("COMPLETE".equals(request.getStatus())) {
                workerRepository.releaseClaim(claim.requestId(), claim.claimToken(), clock.instant());
                return;
            }
            long success = acks.countByErasureRequestIdAndStatus(claim.requestId(), "SUCCESS");
            if (success >= participants.size() && durableEvidenceSatisfied(request)) {
                completeLocal(request);
            }
            workerRepository.releaseClaim(claim.requestId(), claim.claimToken(), clock.instant());
        });
    }

    private void persistDurableRecordNow(UUID requestId) {
        if (durableStore == null) {
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        }
        ErasureRequestEntity request = requests.findById(requestId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
        DurableErasureRecord candidate = DurableErasureRecord.of(
                request.getId(), request.getAuthUserId(), request.getRequestedAt());
        var existing = durableStore.findByRequestId(requestId);
        if (existing.isPresent() && !existing.get().bodyDigest().equals(candidate.bodyDigest())) {
            throw new AuthException(AuthErrorCode.CONFLICT, "ambiguous durable recording retry");
        }
        if (existing.isEmpty()) {
            if (withoutTransaction != null) {
                withoutTransaction.executeWithoutResult(status -> putDurable(candidate));
            } else {
                putDurable(candidate);
            }
        }
        markDurablyRecorded(requestId);
        tryCompleteIfReady(requestId);
    }

    private void schedulePersistAfterCommit(UUID requestId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        persistDurableRecordNow(requestId);
                    } catch (RuntimeException ex) {
                        // The auth transaction has already committed. Keep the public
                        // response truthful: the request is accepted, but recording
                        // remains pending and must be retried independently.
                        log.error("post-commit durable recording failed requestId={}", requestId, ex);
                        schedulePostCommitPersistFailure(requestId);
                    }
                }
            });
        } else if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AuthException(
                    AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable persist cannot run inside an open database transaction");
        } else {
            persistDurableRecordNow(requestId);
        }
    }

    private void markDurablyRecorded(UUID requestId) {
        Runnable update = () -> {
            ErasureRequestEntity request = requests.findById(requestId)
                    .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
            request.markDurablyRecorded();
            requests.save(request);
        };
        if (requiresNew != null) {
            requiresNew.executeWithoutResult(status -> update.run());
        } else {
            update.run();
        }
    }

    private void tryCompleteIfReady(UUID requestId) {
        Runnable work = () -> {
            ErasureRequestEntity request = requests.findById(requestId).orElse(null);
            if (request == null) {
                return;
            }
            long success = acks.countByErasureRequestIdAndStatus(requestId, "SUCCESS");
            if (success >= participants.size()) {
                completeLocal(request);
            }
        };
        // afterCommit may still expose the completed transaction on this thread.
        // Always use a fresh transaction so COMPLETE is committed after a retry.
        if (requiresNew != null) {
            requiresNew.executeWithoutResult(status -> work.run());
        } else {
            work.run();
        }
    }

    private void completeLocalIfAcksPresent(UUID authUserId) {
        requests.findFirstByAuthUserIdOrderByRequestedAtDesc(authUserId).ifPresent(request -> {
            long success = acks.countByErasureRequestIdAndStatus(request.getId(), "SUCCESS");
            if (success >= participants.size()) {
                completeLocal(request);
            }
        });
    }

    private void schedulePostCommitPersistFailure(UUID requestId) {
        if (workerRepository == null || !durableRecordingEnabled) {
            return;
        }
        Instant next = DurableErasureRetryBackoff.nextAttemptAfter(
                clock.instant(), 0, DEFAULT_WORKER_BASE_BACKOFF, DEFAULT_WORKER_MAX_BACKOFF);
        workerRepository.scheduleInitialPersistRetry(requestId, next, "DURABLE_PERSIST_FAILED");
    }

    private void scheduleWorkerRetry(
            ErasureDurableWorkerClaim claim,
            int maxAttempts,
            Duration baseBackoff,
            Duration maxBackoff,
            String errorCode) {
        ErasureRequestEntity row = requests.findById(claim.requestId()).orElse(null);
        int currentAttempts = row == null ? 0 : row.getDurableRetryAttemptCount();
        int nextAttempts = currentAttempts + 1;
        Instant nextAt;
        String code = errorCode;
        if (nextAttempts >= maxAttempts) {
            nextAt = DURABLE_RETRY_EXHAUSTED_NEXT;
            code = "DURABLE_PERSIST_RETRY_EXHAUSTED";
        } else {
            nextAt = DurableErasureRetryBackoff.nextAttemptAfter(
                    clock.instant(), nextAttempts, baseBackoff, maxBackoff);
        }
        workerRepository.recordRetryScheduled(
                claim.requestId(), claim.claimToken(), nextAttempts, nextAt, code);
    }

    private boolean claimStillHeld(ErasureRequestEntity request, ErasureDurableWorkerClaim claim) {
        Instant now = clock.instant();
        return claim.claimToken().equals(request.getDurableWorkerClaimToken())
                && request.getDurableWorkerClaimExpiresAt() != null
                && request.getDurableWorkerClaimExpiresAt().isAfter(now);
    }

    private void completeLocal(ErasureRequestEntity request) {
        if ("COMPLETE".equals(request.getStatus())) {
            return;
        }
        if (!durableEvidenceSatisfied(request)) {
            return;
        }
        if (durableRecordingEnabled && !"DURABLY_RECORDED".equals(request.getDurableRecordingStatus())) {
            request.markDurablyRecorded();
        }
        Instant now = clock.instant();
        AuthUser user = users.findById(request.getAuthUserId()).orElse(null);
        if (user != null) {
            String tombstoneEmail = "erased-" + user.id() + "@invalid.localhost";
            String replacementHash = passwordHasher.hash(UUID.randomUUID().toString());
            user.finishErasure(tombstoneEmail, replacementHash, now);
            users.save(user);
        }
        request.markComplete(now);
        requests.save(request);
        metrics.completed(Duration.between(request.getRequestedAt(), now));
        log.info("erasure completed requestId={} service=auth status=COMPLETE", request.getId());
    }

    private boolean durableEvidenceSatisfied(ErasureRequestEntity request) {
        if (!durableRecordingEnabled) {
            return true;
        }
        if (durableStore == null) {
            return false;
        }
        var recorded = durableStore.findByRequestId(request.getId());
        if (recorded.isEmpty()) {
            return false;
        }
        DurableErasureRecord found = recorded.get();
        return found.erasureRequestId().equals(request.getId())
                && found.authUserId().equals(request.getAuthUserId());
    }

    private static String publicStatus(ErasureRequestEntity row) {
        return switch (row.getStatus()) {
            case "REQUESTED", "IN_PROGRESS" -> "IN_PROGRESS";
            case "COMPLETE" -> "COMPLETE";
            case "FAILED_RETRYING" -> "FAILED_RETRYING";
            default -> "IN_PROGRESS";
        };
    }
}
