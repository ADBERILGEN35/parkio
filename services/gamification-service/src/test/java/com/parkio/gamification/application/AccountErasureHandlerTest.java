package com.parkio.gamification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.gamification.application.event.UserErasureRequestedEvent;
import com.parkio.gamification.application.port.OutboxEventAppender;
import com.parkio.gamification.domain.event.GamificationEvent;
import com.parkio.gamification.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.gamification.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountErasureHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    private final ErasedUserTombstoneJpaRepository tombstones = mock(ErasedUserTombstoneJpaRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OutboxEventAppender outbox = mock(OutboxEventAppender.class);
    private final AccountErasureHandler handler = new AccountErasureHandler(
            tombstones, jdbc, outbox, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void successAckIsQueuedInOutboxAfterLocalErase() {
        when(tombstones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UserErasureRequestedEvent event = new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);

        handler.handle(event);

        InOrder order = inOrder(jdbc, outbox);
        order.verify(jdbc).update("UPDATE point_transactions SET user_id = ? WHERE user_id = ?",
                AccountErasureHandler.ERASED_USER_SENTINEL, event.authUserId());
        ArgumentCaptor<GamificationEvent> appended = ArgumentCaptor.forClass(GamificationEvent.class);
        order.verify(outbox).append(appended.capture());
        UserErasureAcknowledgedEvent ack = (UserErasureAcknowledgedEvent) appended.getValue();
        assertThat(ack.erasureRequestId()).isEqualTo(event.erasureRequestId());
        assertThat(ack.authUserId()).isEqualTo(event.authUserId());
        assertThat(ack.serviceName()).isEqualTo("gamification");
        assertThat(ack.status()).isEqualTo("SUCCESS");
        assertThat(ack.aggregateType()).isEqualTo("AccountErasure");
        assertThat(ack.aggregateId()).isEqualTo(event.erasureRequestId());
        assertThat(ack.eventType()).isEqualTo("UserErasureAcknowledged");
    }

    @Test
    void redeliveredRequestDerivesSameAckEventIdAndReplayDerivesNewOne() {
        when(tombstones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UUID requestId = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = new UserErasureRequestedEvent(UUID.randomUUID(), requestId, user, NOW);
        UserErasureRequestedEvent replay = new UserErasureRequestedEvent(UUID.randomUUID(), requestId, user, NOW);

        handler.handle(event);
        handler.handle(event);
        handler.handle(replay);

        ArgumentCaptor<GamificationEvent> appended = ArgumentCaptor.forClass(GamificationEvent.class);
        verify(outbox, times(3)).append(appended.capture());
        List<UUID> ids = appended.getAllValues().stream().map(GamificationEvent::eventId).toList();
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(ids.get(2)).isNotEqualTo(ids.get(0));
    }

    @Test
    void failedEraseQueuesNoAck() {
        when(tombstones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbc.update(anyString(), (Object[]) any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> handler.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW)))
                .isInstanceOf(IllegalStateException.class);

        verify(outbox, never()).append(any());
    }
}
