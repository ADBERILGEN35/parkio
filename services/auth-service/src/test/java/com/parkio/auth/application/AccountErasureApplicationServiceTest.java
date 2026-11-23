package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.application.port.DurableErasureRecordStore;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.support.InMemoryDurableErasureRecordStore;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.auth.domain.event.UserErasureRequestedEvent;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.domain.exception.LoginLockedException;
import com.parkio.auth.infrastructure.metrics.ErasureMetrics;
import com.parkio.auth.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import com.parkio.auth.infrastructure.persistence.entity.ErasureRequestEntity;
import com.parkio.auth.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureRequestJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureServiceAckJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class AccountErasureApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");
    private static final String PARTICIPANTS = "user";

    @Mock private AuthUserRepository users;
    @Mock private RefreshTokenRepository refreshTokens;
    @Mock private PasswordResetRepository passwordResets;
    @Mock private PasswordHasher passwordHasher;
    @Mock private OutboxEventAppender outbox;
    @Mock private InboxEventRepository inbox;
    @Mock private ErasureRequestJpaRepository requests;
    @Mock private ErasureServiceAckJpaRepository acks;
    @Mock private ErasedUserTombstoneJpaRepository tombstones;

    private AccountErasureApplicationService service;
    private AuthUser user;

    @BeforeEach
    void setUp() {
        service = new AccountErasureApplicationService(
                users,
                refreshTokens,
                passwordResets,
                passwordHasher,
                outbox,
                inbox,
                requests,
                acks,
                tombstones,
                new ErasureMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                true,
                PARTICIPANTS);
        user = AuthUser.register(
                "rider@example.com",
                "hash",
                "vhash",
                NOW.plusSeconds(3600),
                NOW,
                EmailLocale.TR,
                Set.of(new Role(UUID.randomUUID(), RoleName.USER)),
                NOW);
        user.verifyEmail(NOW);
    }

    @Test
    void disabledFlagRejectsDeletion() {
        service = new AccountErasureApplicationService(
                users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, new ErasureMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC), false, PARTICIPANTS);
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.ACCOUNT_ERASURE_DISABLED);
    }

    @Test
    void wrongPasswordDoesNotStartErasure() {
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("bad", "hash")).thenReturn(false);
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "bad"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        verify(outbox, never()).append(any(UserErasureRequestedEvent.class));
    }

    @Test
    void requestLocksLoginRevokesTokensAndPublishesCommand() {
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(requests.findFirstByAuthUserIdOrderByRequestedAtDesc(user.id())).thenReturn(Optional.empty());

        var view = service.requestDeletion(user.id(), "pw");

        assertThat(view.status()).isEqualTo("IN_PROGRESS");
        assertThat(user.status()).isEqualTo(AuthUserStatus.ERASURE_IN_PROGRESS);
        assertThatThrownBy(user::ensureCanAuthenticate)
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.USER_NOT_ACTIVE);
        verify(refreshTokens).revokeAllActiveForUser(
                eq(user.id()), eq(RefreshTokenRevocationReason.ACCOUNT_ERASURE), eq(NOW));
        verify(outbox).append(any(UserErasureRequestedEvent.class));
        verify(tombstones).save(any());
    }

    /** CL-F15 v2: the erasure request removes what the login throttle keeps for the account (known clients too). */
    @Test
    void requestRemovesTheAccountsLoginThrottleState() {
        InMemoryLoginThrottleStore store = new InMemoryLoginThrottleStore(() -> NOW);
        ThrottledLoginFailureTracker tracker = new ThrottledLoginFailureTracker(store, LoginThrottleTestKeys.CURRENT);
        tracker.clearAfterSuccess(user.email(), "198.51.100.7");
        tracker.recordFailure(user.email(), "203.0.113.9", NOW);
        tracker.recordFailure("neighbour@example.com", "203.0.113.9", NOW);
        service.setLoginFailureTracker(tracker);
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(requests.findFirstByAuthUserIdOrderByRequestedAtDesc(user.id())).thenReturn(Optional.empty());

        service.requestDeletion(user.id(), "pw");

        String account = tracker.digest(user.email());
        assertThat(store.keys()).noneMatch(key -> key.contains(account));
        assertThat(store.keys()).anyMatch(key -> key.contains(tracker.digest("neighbour@example.com")));
    }

    /**
     * Best effort: a throttle store that becomes unavailable once the password has been checked does not fail or
     * change the erasure request.
     */
    @Test
    void anUnavailableThrottleStoreDoesNotFailTheErasureRequest() {
        InMemoryLoginThrottleStore store = new InMemoryLoginThrottleStore(() -> NOW);
        service.setLoginFailureTracker(new ThrottledLoginFailureTracker(store, LoginThrottleTestKeys.CURRENT));
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        // The password check runs on the store (#563); the store goes down right after it.
        when(requests.findFirstByAuthUserIdOrderByRequestedAtDesc(user.id())).thenAnswer(invocation -> {
            store.failing = true;
            return Optional.empty();
        });

        assertThat(service.requestDeletion(user.id(), "pw").status()).isEqualTo("IN_PROGRESS");
        verify(outbox).append(any(UserErasureRequestedEvent.class));
        verify(tombstones).save(any());
    }

    /**
     * #563: like a login, the password check needs the throttle store; while the store is unavailable the
     * request fails closed, before the password is compared, and no erasure starts.
     */
    @Test
    void anUnavailableThrottleStoreFailsTheDeletionRequestClosed() {
        service = throttledService();
        throttleStore.failing = true;
        when(users.findById(user.id())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw", CLIENT))
                .isInstanceOf(IllegalStateException.class);
        verify(passwordHasher, never()).matches(any(), any());
        verify(outbox, never()).append(any(UserErasureRequestedEvent.class));
        assertThat(user.status()).isEqualTo(AuthUserStatus.ACTIVE);
    }

    /**
     * #563: wrong passwords on a deletion request meet the login throttle of the account and client, as a failed
     * login and a wrong current password on change-password do (H-06). From the fifth failure the pair waits,
     * answered with the same generic error as a wrong password, and no erasure starts while it waits.
     */
    @Test
    void wrongPasswordsOnADeletionRequestMeetTheLoginThrottle() {
        service = throttledService();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("bad", "hash")).thenReturn(false);
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(requests.findFirstByAuthUserIdOrderByRequestedAtDesc(user.id())).thenReturn(Optional.empty());

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.requestDeletion(user.id(), "bad", CLIENT))
                    .isInstanceOf(AuthException.class)
                    .isNotInstanceOf(LoginLockedException.class)
                    .extracting(e -> ((AuthException) e).errorCode())
                    .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        }
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "bad", CLIENT))
                .isInstanceOf(LoginLockedException.class)
                .hasMessage(AuthErrorCode.INVALID_CREDENTIALS.defaultMessage())
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        assertThat(loginFailures.retryAfter(user.email(), CLIENT, clock.instant())).isEqualTo(Duration.ofSeconds(30));

        // While the pair waits even the right password is refused and not counted; no erasure starts.
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw", CLIENT))
                .isInstanceOf(LoginLockedException.class)
                .hasMessage(AuthErrorCode.INVALID_CREDENTIALS.defaultMessage());
        assertThat(failureCount(CLIENT)).isEqualTo(5);
        assertThat(user.status()).isEqualTo(AuthUserStatus.ACTIVE);
        verify(outbox, never()).append(any(UserErasureRequestedEvent.class));

        // Once the wait has passed the right password starts the erasure as before, and the committed request
        // still removes all of the account's throttle state.
        clock.advance(Duration.ofSeconds(31));
        assertThat(service.requestDeletion(user.id(), "pw", CLIENT).status()).isEqualTo("IN_PROGRESS");
        assertThat(user.status()).isEqualTo(AuthUserStatus.ERASURE_IN_PROGRESS);
        verify(outbox).append(any(UserErasureRequestedEvent.class));
        String account = loginFailures.digest(user.email());
        assertThat(throttleStore.keys()).noneMatch(key -> key.contains(account));
    }

    /**
     * #563: deletion requests and logins draw on one budget per account and client, because both use the login
     * tracker with the account's normalized e-mail: alternating them gives no extra guesses, and a wait started on
     * either holds the other.
     */
    @Test
    void deletionAndLoginFailuresShareOneBudget() {
        service = throttledService();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("bad", "hash")).thenReturn(false);
        for (int i = 0; i < 2; i++) {
            failedLogin(OTHER_CLIENT);
            assertThatThrownBy(() -> service.requestDeletion(user.id(), "bad", OTHER_CLIENT))
                    .isInstanceOf(AuthException.class)
                    .isNotInstanceOf(LoginLockedException.class);
        }
        assertThat(failureCount(OTHER_CLIENT)).isEqualTo(4);
        assertThat(accountFailureCount()).isEqualTo(4);

        // The fifth failure, a login, starts the pair's wait; the deletion request honours it.
        failedLogin(OTHER_CLIENT);
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "bad", OTHER_CLIENT))
                .isInstanceOf(LoginLockedException.class);

        // After the wait a sixth failure, on the deletion request, starts the next wait; the next login meets it.
        clock.advance(Duration.ofSeconds(31));
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "bad", OTHER_CLIENT))
                .isInstanceOf(LoginLockedException.class);
        assertThat(loginFailures.admit(user.email(), OTHER_CLIENT, clock.instant())).isPositive();
        assertThat(failureCount(OTHER_CLIENT)).isEqualTo(6);
        assertThat(accountFailureCount()).isEqualTo(6);
    }

    /** #563: the account-wide wait for unknown clients covers deletion requests; a known client is exempt. */
    @Test
    void aDeletionFromAnUnknownClientWaitsForTheAccountTier() {
        service = throttledService();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(requests.findFirstByAuthUserIdOrderByRequestedAtDesc(user.id())).thenReturn(Optional.empty());
        loginFailures.clearAfterSuccess(user.email(), CLIENT); // CLIENT logged in before, so it is known
        // 50 failed logins spread over 13 clients: no single client reaches its 5-failure tier.
        for (int i = 0; i < 50; i++) {
            failedLogin("192.0.2." + (10 + i / 4));
        }

        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw", OTHER_CLIENT))
                .isInstanceOf(LoginLockedException.class);
        assertThat(failureCount(OTHER_CLIENT)).isZero();
        assertThat(user.status()).isEqualTo(AuthUserStatus.ACTIVE);

        assertThat(service.requestDeletion(user.id(), "pw", CLIENT).status()).isEqualTo("IN_PROGRESS");
    }

    /** A deletion request without a client key uses the account's shared unknown-client pair, as a login does. */
    @Test
    void aDeletionRequestWithoutAClientKeyUsesTheSharedUnknownClient() {
        service = throttledService();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("bad", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.requestDeletion(user.id(), "bad")).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "bad", null)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "bad", " ")).isInstanceOf(AuthException.class);

        assertThat(failureCount(LoginFailureTracker.UNKNOWN_CLIENT)).isEqualTo(3);
    }

    /**
     * The right password on a repeated request, while the erasure is pending, makes no client known (unlike a
     * login or change-password): nothing may write a known-client marker for an account pending erasure
     * (docs/architecture/priv-001-account-data-erasure.md), and the account can never log in again anyway. Below
     * the first tier the request writes no throttle state for the account at all.
     */
    @Test
    void aRepeatedRequestWithTheRightPasswordMakesNoClientKnown() {
        service = throttledService();
        UUID requestId = UUID.randomUUID();
        user.beginErasure(NOW);
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(requests.findFirstByAuthUserIdOrderByRequestedAtDesc(user.id()))
                .thenReturn(Optional.of(new ErasureRequestEntity(requestId, user.id(), "IN_PROGRESS", NOW)));

        assertThat(service.requestDeletion(user.id(), "pw", CLIENT).erasureRequestId()).isEqualTo(requestId);

        String account = loginFailures.digest(user.email());
        assertThat(throttleStore.keys())
                .doesNotContain(loginFailures.knownKey(account, loginFailures.digest(CLIENT)))
                .noneMatch(key -> key.contains(account));
        verify(outbox, never()).append(any(UserErasureRequestedEvent.class));
    }

    /** Without a login throttle the password check stays a plain comparison and is never throttled. */
    @Test
    void withoutALoginThrottleThePasswordCheckIsAPlainComparison() {
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("bad", "hash")).thenReturn(false);
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(requests.findFirstByAuthUserIdOrderByRequestedAtDesc(user.id())).thenReturn(Optional.empty());

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> service.requestDeletion(user.id(), "bad", CLIENT))
                    .isInstanceOf(AuthException.class)
                    .isNotInstanceOf(LoginLockedException.class);
        }
        assertThat(service.requestDeletion(user.id(), "pw", CLIENT).status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void repeatRequestIsIdempotent() {
        UUID requestId = UUID.randomUUID();
        ErasureRequestEntity existing = new ErasureRequestEntity(requestId, user.id(), "IN_PROGRESS", NOW);
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(requests.findFirstByAuthUserIdOrderByRequestedAtDesc(user.id()))
                .thenReturn(Optional.of(existing));

        var view = service.requestDeletion(user.id(), "pw");
        assertThat(view.erasureRequestId()).isEqualTo(requestId);
        verify(outbox, never()).append(any(UserErasureRequestedEvent.class));
    }

    @Test
    void completeOnlyAfterParticipantAck() {
        UUID requestId = UUID.randomUUID();
        ErasureRequestEntity request = new ErasureRequestEntity(requestId, user.id(), "IN_PROGRESS", NOW);
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(true);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(acks.countByErasureRequestIdAndStatus(requestId, "SUCCESS")).thenReturn(1L);
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.hash(any())).thenReturn("replacement-hash");

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), requestId, user.id(), "user", "SUCCESS", NOW));

        assertThat(request.getStatus()).isEqualTo("COMPLETE");
        assertThat(user.status()).isEqualTo(AuthUserStatus.ERASED);
        assertThat(user.email()).startsWith("erased-");
    }

    @Test
    void failedAckDoesNotMarkComplete() {
        UUID requestId = UUID.randomUUID();
        ErasureRequestEntity request = new ErasureRequestEntity(requestId, user.id(), "IN_PROGRESS", NOW);
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(true);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), requestId, user.id(), "user", "FAILED", NOW));

        assertThat(request.getStatus()).isEqualTo("FAILED_RETRYING");
        ArgumentCaptor<AuthUser> saved = ArgumentCaptor.forClass(AuthUser.class);
        verify(users, never()).save(saved.capture());
    }

    @Test
    void unknownParticipantAckIsIgnoredAndDoesNotComplete() {
        UUID requestId = UUID.randomUUID();
        ErasureRequestEntity request = new ErasureRequestEntity(requestId, user.id(), "IN_PROGRESS", NOW);
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(true);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), requestId, user.id(), "forged-service", "SUCCESS", NOW));

        assertThat(request.getStatus()).isEqualTo("IN_PROGRESS");
        verify(acks, never()).save(any());
        verify(users, never()).save(any());
    }

    @Test
    void duplicateAckEventIsIdempotent() {
        UUID requestId = UUID.randomUUID();
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(false);

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), requestId, user.id(), "user", "SUCCESS", NOW));

        verify(acks, never()).save(any());
        verify(users, never()).save(any());
    }

    @Test
    void incompleteParticipantSetNeverCompletes() {
        service = new AccountErasureApplicationService(
                users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, new ErasureMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC), true,
                "user,parking,media,moderation,gamification,notification,analytics,ai-validation");
        UUID requestId = UUID.randomUUID();
        ErasureRequestEntity request = new ErasureRequestEntity(requestId, user.id(), "IN_PROGRESS", NOW);
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(true);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(acks.countByErasureRequestIdAndStatus(requestId, "SUCCESS")).thenReturn(7L);

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), requestId, user.id(), "user", "SUCCESS", NOW));

        assertThat(request.getStatus()).isEqualTo("IN_PROGRESS");
        verify(users, never()).save(any());
    }

    @Test
    void enabledWithoutProviderFailsExplicitly() {
        service = new AccountErasureApplicationService(
                users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, new ErasureMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC), true, PARTICIPANTS, true,
                (DurableErasureRecordStore) null, null, null, (PlatformTransactionManager) null);
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        verify(outbox, never()).append(any(UserErasureRequestedEvent.class));
    }

    @Test
    void flagOffCompletesWithoutDurableEvidence() {
        stubRequestPersistence();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("replacement-hash");
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(true);
        when(acks.countByErasureRequestIdAndStatus(any(), eq("SUCCESS"))).thenReturn(1L);

        var view = service.requestDeletion(user.id(), "pw");
        ErasureRequestEntity row = requestRows.get(view.erasureRequestId());
        assertThat(view.status()).isEqualTo("IN_PROGRESS");
        assertThat(row.getDurableRecordingStatus()).isNull();

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), view.erasureRequestId(), user.id(), "user", "SUCCESS", NOW));
        assertThat(row.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void persistFailureDoesNotComplete() {
        InMemoryDurableErasureRecordStore store = new InMemoryDurableErasureRecordStore();
        store.failNextPuts(1);
        service = durableService(store);
        stubRequestPersistence();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(true);
        when(acks.countByErasureRequestIdAndStatus(any(), eq("SUCCESS"))).thenReturn(1L);

        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        ErasureRequestEntity row = requestRows.values().iterator().next();
        assertThat(row.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(row.getDurableRecordingStatus()).isEqualTo("PENDING_DURABLE");
        assertThat(store.size()).isZero();

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), row.getId(), user.id(), "user", "SUCCESS", NOW));
        assertThat(row.getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void persistThenParticipantAckCompletes() {
        InMemoryDurableErasureRecordStore store = new InMemoryDurableErasureRecordStore();
        service = durableService(store);
        stubRequestPersistence();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("replacement-hash");
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(true);
        when(acks.countByErasureRequestIdAndStatus(any(), eq("SUCCESS"))).thenReturn(1L);

        var view = service.requestDeletion(user.id(), "pw");
        ErasureRequestEntity row = requestRows.get(view.erasureRequestId());
        assertThat(view.status()).isEqualTo("IN_PROGRESS");
        assertThat(row.getDurableRecordingStatus()).isEqualTo("DURABLY_RECORDED");
        assertThat(store.findByRequestId(view.erasureRequestId())).isPresent();

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), view.erasureRequestId(), user.id(), "user", "SUCCESS", NOW));
        assertThat(row.getStatus()).isEqualTo("COMPLETE");
        assertThat(user.status().name()).isEqualTo("ERASED");
    }

    @Test
    void participantAckThenPersistCompletes() {
        InMemoryDurableErasureRecordStore store = new InMemoryDurableErasureRecordStore();
        store.failNextPuts(1);
        service = durableService(store);
        stubRequestPersistence();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("replacement-hash");
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(true);
        when(acks.countByErasureRequestIdAndStatus(any(), eq("SUCCESS"))).thenReturn(1L);

        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw"))
                .isInstanceOf(AuthException.class);
        ErasureRequestEntity row = requestRows.values().iterator().next();
        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), row.getId(), user.id(), "user", "SUCCESS", NOW));
        assertThat(row.getStatus()).isEqualTo("IN_PROGRESS");

        service.persistDurableRecord(row.getId());
        assertThat(row.getDurableRecordingStatus()).isEqualTo("DURABLY_RECORDED");
        assertThat(row.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void persistRetryAfterRestartIsIdempotent() {
        InMemoryDurableErasureRecordStore store = new InMemoryDurableErasureRecordStore();
        store.failNextPuts(1);
        service = durableService(store);
        stubRequestPersistence();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(acks.countByErasureRequestIdAndStatus(any(), eq("SUCCESS"))).thenReturn(0L);

        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw"))
                .isInstanceOf(AuthException.class);
        ErasureRequestEntity row = requestRows.values().iterator().next();
        AccountErasureApplicationService restarted = durableService(store);
        restarted.persistDurableRecord(row.getId());
        restarted.persistDurableRecord(row.getId());
        assertThat(row.getDurableRecordingStatus()).isEqualTo("DURABLY_RECORDED");
        assertThat(row.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void completedRequestDeletionDoesNotReopenOrDuplicate() {
        UUID requestId = UUID.randomUUID();
        ErasureRequestEntity existing = new ErasureRequestEntity(requestId, user.id(), "COMPLETE", NOW);
        existing.markDurablyRecorded();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(requests.findFirstByAuthUserIdOrderByRequestedAtDesc(user.id()))
                .thenReturn(Optional.of(existing));

        var view = service.requestDeletion(user.id(), "pw");
        assertThat(view.erasureRequestId()).isEqualTo(requestId);
        assertThat(view.status()).isEqualTo("COMPLETE");
        verify(outbox, never()).append(any(UserErasureRequestedEvent.class));
        verify(requests, never()).save(any(ErasureRequestEntity.class));
    }

    @Test
    void storeEvidenceCompletesEvenIfColumnStillPending() {
        InMemoryDurableErasureRecordStore store = new InMemoryDurableErasureRecordStore();
        service = durableService(store);
        stubRequestPersistence();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("replacement-hash");
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(true);
        when(acks.countByErasureRequestIdAndStatus(any(), eq("SUCCESS"))).thenReturn(1L);

        store.failNextPuts(1);
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw"))
                .isInstanceOf(AuthException.class);
        ErasureRequestEntity row = requestRows.values().iterator().next();
        store.putIfAbsent(DurableErasureRecord.of(row.getId(), user.id(), row.getRequestedAt()));
        assertThat(row.getDurableRecordingStatus()).isEqualTo("PENDING_DURABLE");

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), row.getId(), user.id(), "user", "SUCCESS", NOW));
        assertThat(row.getStatus()).isEqualTo("COMPLETE");
        assertThat(row.getDurableRecordingStatus()).isEqualTo("DURABLY_RECORDED");
    }

    @Test
    void crashAfterStorePutBeforeStatusUpdateIsRecoverable() {
        InMemoryDurableErasureRecordStore store = new InMemoryDurableErasureRecordStore();
        service = durableService(store);
        stubRequestPersistence();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(acks.countByErasureRequestIdAndStatus(any(), eq("SUCCESS"))).thenReturn(0L);

        store.failNextPuts(1);
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw"))
                .isInstanceOf(AuthException.class);
        ErasureRequestEntity row = requestRows.values().iterator().next();
        store.putIfAbsent(DurableErasureRecord.of(row.getId(), user.id(), row.getRequestedAt()));
        assertThat(row.getDurableRecordingStatus()).isEqualTo("PENDING_DURABLE");

        service.persistDurableRecord(row.getId());
        assertThat(row.getDurableRecordingStatus()).isEqualTo("DURABLY_RECORDED");
        assertThat(row.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void mismatchedStoreIdentityDoesNotSatisfyDurableEvidence() {
        InMemoryDurableErasureRecordStore store = new InMemoryDurableErasureRecordStore();
        service = durableService(store);
        stubRequestPersistence();
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);
        when(inbox.tryClaim(any(), eq(UserErasureAcknowledgedEvent.TYPE), eq(NOW))).thenReturn(true);
        when(acks.countByErasureRequestIdAndStatus(any(), eq("SUCCESS"))).thenReturn(1L);

        store.failNextPuts(1);
        assertThatThrownBy(() -> service.requestDeletion(user.id(), "pw"))
                .isInstanceOf(AuthException.class);
        ErasureRequestEntity row = requestRows.values().iterator().next();
        store.putIfAbsent(DurableErasureRecord.of(row.getId(), UUID.randomUUID(), row.getRequestedAt()));

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), row.getId(), user.id(), "user", "SUCCESS", NOW));
        assertThat(row.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(row.getDurableRecordingStatus()).isEqualTo("PENDING_DURABLE");
    }

    /*
     * U02 #172 review B1: PostgreSQL keeps microseconds and the JDBC driver rounds a nanosecond
     * instant when it writes it, while durable record format v1 truncates erasedAt. A request
     * time with sub-microsecond digits therefore gives the after-commit attempt (entity in
     * memory) and every later attempt (row read back) different records. Both creation paths
     * must carry the database's precision from the start.
     */
    private static final Instant NANOSECOND_NOW = Instant.parse("2026-08-14T09:00:00.123456789Z");

    @Test
    void aNewErasureRequestCarriesMicrosecondPrecisionFromTheStart() {
        service = serviceWithClock(Clock.fixed(NANOSECOND_NOW, ZoneOffset.UTC));
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordHasher.matches("pw", "hash")).thenReturn(true);

        service.requestDeletion(user.id(), "pw");

        Instant micros = NANOSECOND_NOW.truncatedTo(ChronoUnit.MICROS);
        ArgumentCaptor<ErasureRequestEntity> request = ArgumentCaptor.forClass(ErasureRequestEntity.class);
        verify(requests).save(request.capture());
        ArgumentCaptor<ErasedUserTombstoneEntity> tombstone = ArgumentCaptor.forClass(ErasedUserTombstoneEntity.class);
        verify(tombstones).save(tombstone.capture());
        ArgumentCaptor<UserErasureRequestedEvent> event = ArgumentCaptor.forClass(UserErasureRequestedEvent.class);
        verify(outbox).append(event.capture());
        assertThat(request.getValue().getRequestedAt()).isEqualTo(micros);
        assertThat(tombstone.getValue().getErasedAt()).isEqualTo(micros);
        assertThat(event.getValue().occurredAt()).isEqualTo(micros);
    }

    @Test
    void aReplayedErasureRequestCarriesMicrosecondPrecisionFromTheStart() {
        service = serviceWithClock(Clock.fixed(NANOSECOND_NOW, ZoneOffset.UTC));
        when(tombstones.findAll()).thenReturn(List.of(new ErasedUserTombstoneEntity(user.id(), NOW)));
        when(users.findById(user.id())).thenReturn(Optional.of(user));

        assertThat(service.replayTombstones()).isEqualTo(1);

        ArgumentCaptor<ErasureRequestEntity> request = ArgumentCaptor.forClass(ErasureRequestEntity.class);
        verify(requests).save(request.capture());
        assertThat(request.getValue().getRequestedAt()).isEqualTo(NANOSECOND_NOW.truncatedTo(ChronoUnit.MICROS));
    }

    private AccountErasureApplicationService serviceWithClock(Clock clock) {
        return new AccountErasureApplicationService(
                users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, new ErasureMetrics(new SimpleMeterRegistry()),
                clock, true, PARTICIPANTS);
    }

    private static final String CLIENT = "198.51.100.7";
    private static final String OTHER_CLIENT = "203.0.113.9";

    private MutableClock clock;
    private InMemoryLoginThrottleStore throttleStore;
    private ThrottledLoginFailureTracker loginFailures;

    /** The service with the real login throttle on an in-memory store, both on a clock the test can advance. */
    private AccountErasureApplicationService throttledService() {
        clock = new MutableClock(NOW);
        throttleStore = new InMemoryLoginThrottleStore(clock);
        loginFailures = new ThrottledLoginFailureTracker(throttleStore, LoginThrottleTestKeys.CURRENT);
        AccountErasureApplicationService throttled = serviceWithClock(clock);
        throttled.setLoginFailureTracker(loginFailures);
        return throttled;
    }

    /** What {@code AuthApplicationService.login} does with a wrong password for the account: admit, then record. */
    private void failedLogin(String client) {
        assertThat(loginFailures.admit(user.email(), client, clock.instant())).isZero();
        loginFailures.recordFailure(user.email(), client, clock.instant());
    }

    private long failureCount(String client) {
        return throttleStore.counter(loginFailures.pairFailuresKey(
                loginFailures.digest(user.email()), loginFailures.digest(client)));
    }

    private long accountFailureCount() {
        return throttleStore.counter(loginFailures.accountFailuresKey(loginFailures.digest(user.email())));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private final Map<UUID, ErasureRequestEntity> requestRows = new HashMap<>();

    private AccountErasureApplicationService durableService(DurableErasureRecordStore store) {
        return new AccountErasureApplicationService(
                users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, new ErasureMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC), true, PARTICIPANTS, true, store, null, null, null);
    }

    private void stubRequestPersistence() {
        when(requests.save(any(ErasureRequestEntity.class))).thenAnswer(inv -> {
            ErasureRequestEntity row = inv.getArgument(0);
            requestRows.put(row.getId(), row);
            return row;
        });
        when(requests.findById(any())).thenAnswer(inv -> Optional.ofNullable(requestRows.get(inv.getArgument(0))));
        when(requests.findFirstByAuthUserIdOrderByRequestedAtDesc(any())).thenAnswer(inv ->
                requestRows.values().stream()
                        .filter(row -> row.getAuthUserId().equals(inv.getArgument(0)))
                        .findFirst());
    }
}
