package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.auth.domain.event.UserErasureRequestedEvent;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.metrics.ErasureMetrics;
import com.parkio.auth.infrastructure.persistence.entity.ErasureRequestEntity;
import com.parkio.auth.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureRequestJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureServiceAckJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
