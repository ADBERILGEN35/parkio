package com.parkio.aivalidation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.aivalidation.application.event.UserErasureRequestedEvent;
import com.parkio.aivalidation.application.port.ErasureAckOutbox;
import com.parkio.aivalidation.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.aivalidation.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountErasureHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    private ErasedUserTombstoneJpaRepository tombstones;
    private JdbcTemplate jdbc;
    private ErasureAckOutbox ackOutbox;
    private AccountErasureHandler handler;

    @BeforeEach
    void setUp() {
        tombstones = mock(ErasedUserTombstoneJpaRepository.class);
        jdbc = mock(JdbcTemplate.class);
        ackOutbox = mock(ErasureAckOutbox.class);
        when(tombstones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        handler = new AccountErasureHandler(tombstones, jdbc, ackOutbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void successAckIsQueuedAfterLocalErase() {
        UserErasureRequestedEvent event = event();

        handler.handle(event);

        InOrder order = inOrder(tombstones, ackOutbox);
        order.verify(tombstones).save(any());
        ArgumentCaptor<UserErasureAcknowledgedEvent> ack = ArgumentCaptor.forClass(UserErasureAcknowledgedEvent.class);
        order.verify(ackOutbox).append(ack.capture());
        assertThat(ack.getValue().eventId()).isEqualTo(AccountErasureHandler.ackEventId(event));
        assertThat(ack.getValue().erasureRequestId()).isEqualTo(event.erasureRequestId());
        assertThat(ack.getValue().authUserId()).isEqualTo(event.authUserId());
        assertThat(ack.getValue().serviceName()).isEqualTo("ai-validation");
        assertThat(ack.getValue().status()).isEqualTo("SUCCESS");
    }

    @Test
    void redeliveryRerunsEraseWithSameAckIdAndReplayGetsNewId() {
        UserErasureRequestedEvent event = event();
        UserErasureRequestedEvent replay = new UserErasureRequestedEvent(
                UUID.randomUUID(), event.erasureRequestId(), event.authUserId(), NOW);

        handler.handle(event);
        handler.handle(event);
        handler.handle(replay);

        verify(tombstones, times(3)).save(any());
        ArgumentCaptor<UserErasureAcknowledgedEvent> acks = ArgumentCaptor.forClass(UserErasureAcknowledgedEvent.class);
        verify(ackOutbox, times(3)).append(acks.capture());
        List<UUID> ids = acks.getAllValues().stream().map(UserErasureAcknowledgedEvent::eventId).toList();
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(ids.get(2)).isNotEqualTo(ids.get(0));
    }

    @Test
    void failedEraseQueuesNoAck() {
        when(tombstones.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> handler.handle(event())).isInstanceOf(IllegalStateException.class);

        verify(ackOutbox, never()).append(any());
    }

    @Test
    void ackOutboxFailurePropagatesSoTheEraseRollsBack() {
        doThrow(new IllegalStateException("outbox down")).when(ackOutbox).append(any());

        assertThatThrownBy(() -> handler.handle(event())).isInstanceOf(IllegalStateException.class);
    }

    private static UserErasureRequestedEvent event() {
        return new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);
    }
}
