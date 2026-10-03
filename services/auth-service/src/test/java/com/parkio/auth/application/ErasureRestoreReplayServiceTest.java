package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.port.ErasureRestoreRepository;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.domain.event.UserErasureRestoreAcknowledgedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Restore replay is off by default and refuses malformed input before touching anything. */
class ErasureRestoreReplayServiceTest {

    private static final String PARTICIPANTS = "user,parking,media,moderation,gamification,notification,analytics,ai-validation";

    private final ErasureRestoreRepository restores = mock(ErasureRestoreRepository.class);
    private final OutboxEventAppender outbox = mock(OutboxEventAppender.class);
    private final InboxEventRepository inbox = mock(InboxEventRepository.class);

    @Test
    void aDisabledCoordinatorStartsNothingAndStoresNoAcknowledgement() {
        ErasureRestoreReplayService disabled = service(false);
        UUID user = UUID.randomUUID();

        assertThatThrownBy(() -> disabled.startRestoreReplay(UUID.randomUUID(), "dataset",
                List.of(new ErasureLedgerEntry(user, Instant.parse("2026-09-29T08:16:00Z")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restore-replay.enabled=false");
        disabled.handleAcknowledgement(new UserErasureRestoreAcknowledgedEvent(UUID.randomUUID(), UUID.randomUUID(),
                "dataset", "0".repeat(64), user, "gamification", "SUCCESS", Instant.now()));

        verifyNoInteractions(restores, outbox, inbox);
    }

    @Test
    void aBlankDatasetOrASetWithOneUserTwiceIsRefused() {
        ErasureRestoreReplayService enabled = service(true);
        UUID user = UUID.randomUUID();
        List<ErasureLedgerEntry> twice = List.of(
                new ErasureLedgerEntry(user, Instant.parse("2026-09-29T08:16:00Z")),
                new ErasureLedgerEntry(user, Instant.parse("2026-09-29T08:17:00Z")));

        assertThatThrownBy(() -> enabled.startRestoreReplay(UUID.randomUUID(), " ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled.startRestoreReplay(UUID.randomUUID(), "dataset", twice))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(outbox);
        verify(restores, never()).insertAttempt(any(), any());
    }

    private ErasureRestoreReplayService service(boolean enabled) {
        return new ErasureRestoreReplayService(restores, outbox, inbox, Clock.systemUTC(), enabled, PARTICIPANTS);
    }
}
