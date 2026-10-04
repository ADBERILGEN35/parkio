package com.parkio.auth.application;

import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.DurableErasurePutResult;
import com.parkio.auth.application.port.DurableErasureReceipt;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.application.port.DurableErasureRecordStore;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.result.AccountDeletionStatusView;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
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
    private final EntityManagerFactory entityManagerFactory;
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
                false, (DurableErasureRecordStore) null, null, null, (PlatformTransactionManager) null);
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
            EntityManagerFactory entityManagerFactory,
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
        this.entityManagerFactory = entityManagerFactory;
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
            ObjectProvider<EntityManagerFactory> entityManagerFactories,
            ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this(users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, metrics, clock, enabled, participantsCsv,
                durableRecordingEnabled, durableStores.getIfAvailable(),
                workerRepositories.getIfAvailable(), entityManagerFactories.getIfAvailable(),
                transactionManagers.getIfAvailable());
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
        Instant now = erasureTime();
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
        Instant now = erasureTime();
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

    /**
     * Auth's own share of a restore replay (docs/architecture/erasure-restore-replay-contract.md):
     * brings one user of a trusted erasure set back to the erased end state in a restored database.
     * The tombstone is kept, or recreated with the original {@code erasedAt}; active refresh and
     * reset tokens are revoked; the account's login identifiers are replaced as when an erasure
     * completes. Erasure request rows and their public status are not created or changed. Idempotent.
     * Runs only inside the caller's transaction, so the caller can commit it together with auth's
     * restore ACK.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replayLocalErasureForRestore(UUID authUserId, Instant erasedAt) {
        Instant now = erasureTime();
        if (!tombstones.existsById(authUserId)) {
            tombstones.save(new ErasedUserTombstoneEntity(authUserId, erasedAt));
        }
        refreshTokens.revokeAllActiveForUser(authUserId, RefreshTokenRevocationReason.ACCOUNT_ERASURE, now);
        passwordResets.consumeActiveForUser(authUserId, now);
        AuthUser user = users.findById(authUserId).orElse(null);
        if (user == null || user.status() == AuthUserStatus.ERASED) {
            return;
        }
        user.beginErasure(now);
        String tombstoneEmail = "erased-" + user.id() + "@invalid.localhost";
        user.finishErasure(tombstoneEmail, passwordHasher.hash(UUID.randomUUID().toString()), now);
        users.save(user);
        log.info("erasure restore replay service=auth status=ERASED");
    }

    /**
     * The time a new erasure request, its tombstone and its event carry, at PostgreSQL's
     * microsecond precision. The JDBC driver rounds a nanosecond instant when it stores it, while
     * evidence format v1 truncates {@code erasedAt}; with sub-microsecond digits the after-commit
     * attempt (entity still in memory) and every later attempt (row read back) would build
     * different durable records, and a retry after a partial publication would be a conflict.
     */
    private Instant erasureTime() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
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
        DurableErasureReceipt receipt = result.receipt();
        log.info("erasure durable receipt requestId={} created={} versionId={} sha256={} lock={} until={}",
                candidate.erasureRequestId(), result.created(), receipt.versionId(), receipt.sha256(),
                receipt.retentionMode(), receipt.retainUntil());
    }

    /**
     * Worker entry: persist a claimed PENDING_DURABLE row. Store I/O is outside any database
     * transaction; mutation re-enters a short TX and revalidates claim ownership.
     */
    public void processWorkerPersistClaim(
            ErasureDurableWorkerClaim claim, int maxAttempts, Duration baseBackoff, Duration maxBackoff) {
        if (workerRepository == null || durableStore == null || !durableRecordingEnabled) {
            return;
        }
        try {
            DurableErasureRecord candidate = DurableErasureRecord.of(
                    claim.requestId(), claim.authUserId(), claim.requestedAt());
            DurableEvidence evidence = readDurableEvidenceOutsideTransaction(claim.requestId());
            if (evidence.found() && !evidence.matches(claim.requestId(), claim.authUserId())) {
                scheduleWorkerRetry(
                        claim, maxAttempts, baseBackoff, maxBackoff, "DURABLE_RECORDING_CONFLICT");
                return;
            }
            if (!evidence.found()) {
                if (withoutTransaction != null) {
                    withoutTransaction.executeWithoutResult(status -> putDurable(candidate));
                } else {
                    putDurable(candidate);
                }
                evidence = readDurableEvidenceOutsideTransaction(claim.requestId());
                if (!evidence.matches(claim.requestId(), claim.authUserId())) {
                    scheduleWorkerRetry(
                            claim, maxAttempts, baseBackoff, maxBackoff, "DURABLE_RECORDING_CONFLICT");
                    return;
                }
            }
            int marked = workerRepository.markDurablyRecordedIfClaimed(
                    claim.requestId(), claim.claimToken(), clock.instant());
            if (marked == 0) {
                // Another path may already have marked DURABLY_RECORDED; still try COMPLETE.
                if (workerRepository.claimStillActive(
                        claim.requestId(), claim.claimToken(), clock.instant())) {
                    tryCompleteIfReady(claim.requestId());
                    workerRepository.releaseClaim(
                            claim.requestId(), claim.claimToken(), clock.instant());
                }
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
        DurableEvidence evidence = readDurableEvidenceOutsideTransaction(claim.requestId());
        if (!evidence.matches(claim.requestId(), claim.authUserId())) {
            workerRepository.releaseClaim(claim.requestId(), claim.claimToken(), clock.instant());
            return;
        }
        requiresNew.executeWithoutResult(tx -> {
            detachCachedErasureRows();
            ErasureRequestEntity request = requests.findById(claim.requestId()).orElse(null);
            if (request == null
                    || !claim.requestId().equals(request.getId())
                    || !claim.authUserId().equals(request.getAuthUserId())
                    || "COMPLETE".equals(request.getStatus())) {
                return;
            }
            Instant requestedAt = request.getRequestedAt();
            detachCachedErasureRows();
            Instant now = clock.instant();
            if (!workerRepository.claimStillActive(claim.requestId(), claim.claimToken(), now)) {
                return;
            }
            long success = acks.countByErasureRequestIdAndStatus(claim.requestId(), "SUCCESS");
            if (success < participants.size()) {
                workerRepository.releaseClaim(claim.requestId(), claim.claimToken(), clock.instant());
                return;
            }
            AuthUser user = users.findById(claim.authUserId()).orElse(null);
            if (user != null && !claim.authUserId().equals(user.id())) {
                tx.setRollbackOnly();
                return;
            }
            Instant mutationTime = clock.instant();
            int completed = workerRepository.markCompleteIfClaimed(
                    claim.requestId(), claim.claimToken(), mutationTime, mutationTime);
            if (completed == 0) {
                tx.setRollbackOnly();
                return;
            }
            if (user != null) {
                String tombstoneEmail = "erased-" + user.id() + "@invalid.localhost";
                String replacementHash = passwordHasher.hash(UUID.randomUUID().toString());
                user.finishErasure(tombstoneEmail, replacementHash, mutationTime);
                users.save(user);
            }
            metrics.completed(Duration.between(requestedAt, mutationTime));
            log.info("erasure completed requestId={} service=auth status=COMPLETE", claim.requestId());
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
        DurableEvidence existing = readDurableEvidenceOutsideTransaction(requestId);
        if (existing.found() && !existing.matches(request.getId(), request.getAuthUserId())) {
            throw new AuthException(AuthErrorCode.CONFLICT, "ambiguous durable recording retry");
        }
        if (!existing.found()) {
            if (withoutTransaction != null) {
                withoutTransaction.executeWithoutResult(status -> putDurable(candidate));
            } else {
                putDurable(candidate);
            }
            DurableEvidence afterPut = readDurableEvidenceOutsideTransaction(requestId);
            if (!afterPut.matches(request.getId(), request.getAuthUserId())) {
                throw new AuthException(AuthErrorCode.CONFLICT, "ambiguous durable recording retry");
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
            detachCachedErasureRows();
            ErasureRequestEntity request = requests.findById(requestId)
                    .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
            if ("COMPLETE".equals(request.getStatus())) {
                return;
            }
            request.markDurablyRecorded();
            request.clearDurableRetryDelay();
            requests.save(request);
        };
        if (requiresNew != null) {
            requiresNew.executeWithoutResult(status -> update.run());
        } else {
            update.run();
        }
    }

    private void tryCompleteIfReady(UUID requestId) {
        DurableEvidence evidence = durableRecordingEnabled
                ? readDurableEvidenceOutsideTransaction(requestId)
                : DurableEvidence.notRequired();
        Runnable work = () -> {
            detachCachedErasureRows();
            ErasureRequestEntity request = requests.findById(requestId).orElse(null);
            if (request == null || "COMPLETE".equals(request.getStatus())) {
                return;
            }
            long success = acks.countByErasureRequestIdAndStatus(requestId, "SUCCESS");
            if (success < participants.size()) {
                return;
            }
            if (durableRecordingEnabled
                    && !evidence.matches(request.getId(), request.getAuthUserId())) {
                return;
            }
            completeLocalWithVerifiedEvidence(request);
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
        // Cap exponential backoff; never abandon the pending erasure row.
        int backoffSteps = Math.min(nextAttempts, Math.max(1, maxAttempts));
        Instant nextAt = DurableErasureRetryBackoff.nextAttemptAfter(
                clock.instant(), backoffSteps, baseBackoff, maxBackoff);
        workerRepository.recordRetryScheduled(
                claim.requestId(),
                claim.claimToken(),
                clock.instant(),
                nextAttempts,
                nextAt,
                errorCode);
    }

    private boolean claimStillHeld(ErasureRequestEntity request, ErasureDurableWorkerClaim claim) {
        Instant now = clock.instant();
        return claim.claimToken().equals(request.getDurableWorkerClaimToken())
                && request.getDurableWorkerClaimExpiresAt() != null
                && request.getDurableWorkerClaimExpiresAt().isAfter(now);
    }

    /**
     * Completes only after durable evidence has already been verified outside a database
     * transaction (or durable recording is disabled).
     */
    private void completeLocal(ErasureRequestEntity request) {
        if ("COMPLETE".equals(request.getStatus())) {
            return;
        }
        if (durableRecordingEnabled) {
            DurableEvidence evidence = readDurableEvidenceOutsideTransaction(request.getId());
            if (!evidence.matches(request.getId(), request.getAuthUserId())) {
                return;
            }
        }
        completeLocalWithVerifiedEvidence(request);
    }

    private void completeLocalWithVerifiedEvidence(ErasureRequestEntity request) {
        if ("COMPLETE".equals(request.getStatus())) {
            return;
        }
        if (durableRecordingEnabled && !"DURABLY_RECORDED".equals(request.getDurableRecordingStatus())) {
            request.markDurablyRecorded();
            request.clearDurableRetryDelay();
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

    private void detachCachedErasureRows() {
        if (entityManagerFactory == null) {
            return;
        }
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        if (em != null) {
            em.clear();
        }
    }

    private DurableEvidence readDurableEvidenceOutsideTransaction(UUID requestId) {
        if (!durableRecordingEnabled) {
            return DurableEvidence.notRequired();
        }
        if (durableStore == null) {
            return DurableEvidence.missing();
        }
        if (withoutTransaction != null) {
            return withoutTransaction.execute(status -> loadDurableEvidence(requestId));
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AuthException(
                    AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable store read cannot run inside an open database transaction");
        }
        return loadDurableEvidence(requestId);
    }

    private DurableEvidence loadDurableEvidence(UUID requestId) {
        return durableStore
                .findByRequestId(requestId)
                .map(DurableEvidence::found)
                .orElseGet(DurableEvidence::missing);
    }

    private record DurableEvidence(boolean required, DurableErasureRecord record) {
        static DurableEvidence notRequired() {
            return new DurableEvidence(false, null);
        }

        static DurableEvidence missing() {
            return new DurableEvidence(true, null);
        }

        static DurableEvidence found(DurableErasureRecord record) {
            return new DurableEvidence(true, record);
        }

        boolean found() {
            return record != null;
        }

        boolean matches(UUID requestId, UUID authUserId) {
            if (!required) {
                return true;
            }
            return record != null
                    && record.erasureRequestId().equals(requestId)
                    && record.authUserId().equals(authUserId);
        }
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
