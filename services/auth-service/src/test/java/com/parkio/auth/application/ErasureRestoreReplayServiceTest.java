package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.port.ErasureRestoreRepository;
import com.parkio.auth.application.port.ErasureRestoreRepository.RestoreAttempt;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.domain.event.UserErasureRestoreAcknowledgedEvent;
import com.parkio.auth.domain.event.UserErasureRestoreReplayRequestedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Restore replay is off by default and refuses malformed input before touching anything. An
 * attempt requires auth plus every contract participant, fixed at its start, and auth's own share
 * is replayed and acknowledged in the start transaction.
 */
class ErasureRestoreReplayServiceTest {

    private static final String PARTICIPANTS = "user,parking,media,moderation,gamification,notification,analytics,ai-validation";
    private static final Instant NOW = Instant.parse("2026-10-03T09:00:00Z");

    private final ErasureRestoreRepository restores = mock(ErasureRestoreRepository.class);
    private final OutboxEventAppender outbox = mock(OutboxEventAppender.class);
    private final InboxEventRepository inbox = mock(InboxEventRepository.class);
    private final AccountErasureApplicationService accountErasure = mock(AccountErasureApplicationService.class);

    @Test
    void aDisabledCoordinatorStartsNothingAndStoresNoAcknowledgement() {
        ErasureRestoreReplayService disabled = service(false, PARTICIPANTS);
        UUID user = UUID.randomUUID();

        assertThatThrownBy(() -> disabled.startRestoreReplay(UUID.randomUUID(), "dataset",
                List.of(new ErasureLedgerEntry(user, Instant.parse("2026-09-29T08:16:00Z")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restore-replay.enabled=false");
        disabled.handleAcknowledgement(new UserErasureRestoreAcknowledgedEvent(UUID.randomUUID(), UUID.randomUUID(),
                "dataset", "0".repeat(64), user, "gamification", "SUCCESS", Instant.now()));

        verifyNoInteractions(restores, outbox, inbox, accountErasure);
    }

    @Test
    void aBlankDatasetOrASetWithOneUserTwiceIsRefused() {
        ErasureRestoreReplayService enabled = service(true, PARTICIPANTS);
        UUID user = UUID.randomUUID();
        List<ErasureLedgerEntry> twice = List.of(
                new ErasureLedgerEntry(user, Instant.parse("2026-09-29T08:16:00Z")),
                new ErasureLedgerEntry(user, Instant.parse("2026-09-29T08:17:00Z")));

        assertThatThrownBy(() -> enabled.startRestoreReplay(UUID.randomUUID(), " ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled.startRestoreReplay(UUID.randomUUID(), "dataset", twice))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(outbox, accountErasure);
        verify(restores, never()).insertAttempt(any(), any(), any());
    }

    @Test
    void anEnabledCoordinatorRefusesAnEmptyOrIncompleteParticipantSet() {
        assertThatThrownBy(() -> service(true, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing [user, parking, media, moderation, gamification, notification, analytics, ai-validation]");
        assertThatThrownBy(() -> service(true, "gamification,user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing [parking, media, moderation, notification, analytics, ai-validation]");
        assertThatThrownBy(() -> service(true, PARTICIPANTS.replace(",ai-validation", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing [ai-validation]");
        // The live path shares the participant setting; with restore replay off nothing is refused.
        assertThatCode(() -> service(false, "gamification,user")).doesNotThrowAnyException();
        assertThatCode(() -> service(true, PARTICIPANTS + ",billing")).doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    @Test
    void anAttemptRequiresAuthPlusEveryContractParticipantAndReplaysAuthsShareWithItsAck() {
        ErasureRestoreReplayService enabled = service(true, PARTICIPANTS);
        UUID attemptId = UUID.randomUUID();
        ErasureLedgerEntry first = new ErasureLedgerEntry(UUID.randomUUID(), Instant.parse("2026-09-29T08:16:00Z"));
        ErasureLedgerEntry second = new ErasureLedgerEntry(UUID.randomUUID(), Instant.parse("2026-09-29T08:17:00Z"));
        when(restores.findAttempt(attemptId)).thenReturn(Optional.empty());

        enabled.startRestoreReplay(attemptId, "dataset", List.of(first, second));

        ArgumentCaptor<Collection<String>> required = ArgumentCaptor.forClass(Collection.class);
        verify(restores).insertAttempt(any(), eq(List.of(first, second)), required.capture());
        assertThat(required.getValue()).containsExactlyInAnyOrder("auth", "user", "parking", "media", "moderation",
                "gamification", "notification", "analytics", "ai-validation");
        InOrder order = inOrder(accountErasure, restores, outbox);
        for (ErasureLedgerEntry entry : List.of(first, second)) {
            order.verify(accountErasure).replayLocalErasureForRestore(entry.authUserId(), entry.erasedAt());
            order.verify(restores).upsertAck(attemptId, entry.authUserId(), "auth", "SUCCESS", NOW);
            order.verify(outbox).append(any(UserErasureRestoreReplayRequestedEvent.class));
        }
    }

    @Test
    void anAcknowledgementClaimingToBeAuthIsIgnored() {
        ErasureRestoreReplayService enabled = service(true, PARTICIPANTS);
        UUID attemptId = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(inbox.tryClaim(any(), anyString(), any())).thenReturn(true);
        when(restores.findAttempt(attemptId)).thenReturn(Optional.of(
                new RestoreAttempt(attemptId, "dataset", "0".repeat(64), 1, NOW)));
        when(restores.requiredParticipants(attemptId)).thenReturn(List.of("auth", "gamification"));
        when(restores.isAttemptUser(attemptId, user)).thenReturn(true);

        enabled.handleAcknowledgement(new UserErasureRestoreAcknowledgedEvent(UUID.randomUUID(), attemptId,
                "dataset", "0".repeat(64), user, "auth", "FAILED", NOW));

        verify(restores, never()).upsertAck(any(), any(), any(), any(), any());
    }

    @Test
    void theVerdictUsesTheParticipantsFixedAtStartNotTheCurrentConfiguration() {
        UUID attemptId = UUID.randomUUID();
        when(restores.findAttempt(attemptId)).thenReturn(Optional.of(
                new RestoreAttempt(attemptId, "dataset", "0".repeat(64), 1, NOW)));
        when(restores.requiredParticipants(attemptId)).thenReturn(List.of("auth", "gamification", "media"));
        when(restores.countAcksByService(attemptId, "SUCCESS")).thenReturn(Map.of("auth", 1L, "gamification", 1L));
        when(restores.countAcksByService(attemptId, "FAILED")).thenReturn(Map.of());

        // Configured differently now (more participants): the attempt keeps its own set.
        RestoreReplayVerdict verdict = service(true, PARTICIPANTS + ",billing").verdict(attemptId);

        assertThat(verdict.status()).isEqualTo(RestoreReplayVerdict.Status.BLOCKED);
        assertThat(verdict.missing()).containsExactly(Map.entry("media", 1L));
    }

    @Test
    void anAttemptWithoutRecordedParticipantsIsNeverComplete() {
        UUID attemptId = UUID.randomUUID();
        when(restores.findAttempt(attemptId)).thenReturn(Optional.of(
                new RestoreAttempt(attemptId, "dataset", "0".repeat(64), 0, NOW)));
        when(restores.requiredParticipants(attemptId)).thenReturn(List.of());

        RestoreReplayVerdict verdict = service(true, PARTICIPANTS).verdict(attemptId);

        assertThat(verdict.status()).isEqualTo(RestoreReplayVerdict.Status.BLOCKED);
        assertThat(verdict.reason()).isEqualTo("no required participants recorded for this attempt");
    }

    private ErasureRestoreReplayService service(boolean enabled, String participants) {
        return new ErasureRestoreReplayService(restores, outbox, inbox, accountErasure,
                Clock.fixed(NOW, ZoneOffset.UTC), enabled, participants);
    }
}
