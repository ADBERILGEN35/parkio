package com.parkio.auth.application;

import com.parkio.auth.application.port.AuthUserRepository;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                    String participantsCsv) {
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
    }

    @Transactional
    public AccountDeletionStatusView requestDeletion(UUID principalUserId, String password) {
        if (!enabled) {
            throw new AuthException(AuthErrorCode.ACCOUNT_ERASURE_DISABLED);
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
        requests.save(request);
        outbox.append(UserErasureRequestedEvent.of(requestId, user.id(), now));
        metrics.requested();
        log.info("erasure requested requestId={} service=auth status=IN_PROGRESS", requestId);
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
                    requests.save(new ErasureRequestEntity(requestId, user.id(), "IN_PROGRESS", now));
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

    private void completeLocalIfAcksPresent(UUID authUserId) {
        requests.findFirstByAuthUserIdOrderByRequestedAtDesc(authUserId).ifPresent(request -> {
            long success = acks.countByErasureRequestIdAndStatus(request.getId(), "SUCCESS");
            if (success >= participants.size()) {
                completeLocal(request);
            }
        });
    }

    private void completeLocal(ErasureRequestEntity request) {
        if ("COMPLETE".equals(request.getStatus())) {
            return;
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

    private static String publicStatus(ErasureRequestEntity row) {
        return switch (row.getStatus()) {
            case "REQUESTED", "IN_PROGRESS" -> "IN_PROGRESS";
            case "COMPLETE" -> "COMPLETE";
            case "FAILED_RETRYING" -> "FAILED_RETRYING";
            default -> "IN_PROGRESS";
        };
    }
}
