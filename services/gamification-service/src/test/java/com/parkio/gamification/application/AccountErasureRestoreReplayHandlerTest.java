package com.parkio.gamification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.gamification.application.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.gamification.application.port.OutboxEventAppender;
import com.parkio.gamification.domain.event.GamificationEvent;
import com.parkio.gamification.domain.event.UserErasureRestoreAcknowledgedEvent;
import com.parkio.gamification.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

/** The restore replay runs the live erase and then queues an attempt-bound restore ACK. */
class AccountErasureRestoreReplayHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    private final ErasedUserTombstoneJpaRepository tombstones = mock(ErasedUserTombstoneJpaRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OutboxEventAppender outbox = mock(OutboxEventAppender.class);
    private final AccountErasureHandler handler = new AccountErasureHandler(
            tombstones, jdbc, outbox, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theRestoreAckEchoesTheAttemptAndIsQueuedAfterTheErase() {
        when(tombstones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UserErasureRestoreReplayRequestedEvent event = replay();

        handler.replayForRestore(event);

        InOrder order = inOrder(jdbc, outbox);
        order.verify(jdbc).update("UPDATE point_transactions SET user_id = ? WHERE user_id = ?",
                AccountErasureHandler.ERASED_USER_SENTINEL, event.authUserId());
        ArgumentCaptor<GamificationEvent> appended = ArgumentCaptor.forClass(GamificationEvent.class);
        order.verify(outbox).append(appended.capture());
        UserErasureRestoreAcknowledgedEvent ack = (UserErasureRestoreAcknowledgedEvent) appended.getValue();
        assertThat(ack.recoveryAttemptId()).isEqualTo(event.recoveryAttemptId());
        assertThat(ack.restoredDatasetId()).isEqualTo(event.restoredDatasetId());
        assertThat(ack.erasureSetDigest()).isEqualTo(event.erasureSetDigest());
        assertThat(ack.authUserId()).isEqualTo(event.authUserId());
        assertThat(ack.serviceName()).isEqualTo("gamification");
        assertThat(ack.status()).isEqualTo("SUCCESS");
        assertThat(ack.occurredAt()).isEqualTo(NOW);
        assertThat(ack.eventType()).isEqualTo("UserErasureRestoreAcknowledged");
        assertThat(ack.aggregateType()).isEqualTo("AccountErasure");
        assertThat(ack.aggregateId()).isEqualTo(event.authUserId());
    }

    @Test
    void aRedeliveryReDerivesTheSameAckIdAndAnotherReplayEventAFreshOne() {
        UserErasureRestoreReplayRequestedEvent event = replay();
        UserErasureRestoreReplayRequestedEvent redelivered = new UserErasureRestoreReplayRequestedEvent(
                event.eventId(), event.recoveryAttemptId(), event.restoredDatasetId(), event.erasureSetDigest(),
                event.authUserId(), event.erasedAt(), event.occurredAt());
        UserErasureRestoreReplayRequestedEvent anotherAttempt = new UserErasureRestoreReplayRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), event.restoredDatasetId(), event.erasureSetDigest(),
                event.authUserId(), event.erasedAt(), event.occurredAt());

        assertThat(AccountErasureHandler.restoreAckEventId(redelivered))
                .isEqualTo(AccountErasureHandler.restoreAckEventId(event));
        assertThat(AccountErasureHandler.restoreAckEventId(anotherAttempt))
                .isNotEqualTo(AccountErasureHandler.restoreAckEventId(event));
    }

    private static UserErasureRestoreReplayRequestedEvent replay() {
        return new UserErasureRestoreReplayRequestedEvent(UUID.randomUUID(), UUID.randomUUID(),
                "backup-stamp-2026-10-03", "a".repeat(64), UUID.randomUUID(),
                Instant.parse("2026-09-29T08:16:00Z"), NOW);
    }
}
