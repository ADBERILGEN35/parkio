package com.parkio.media.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.media.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.media.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.media.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ErasureAckOutboxAdapterTest {

    private final OutboxEventJpaRepository jpa = mock(OutboxEventJpaRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ErasureAckOutboxAdapter adapter = new ErasureAckOutboxAdapter(jpa, objectMapper);

    @Test
    void writesAckRowKeyedByRequest() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        adapter.append(new UserErasureAcknowledgedEvent(
                eventId, requestId, user, "media", "SUCCESS", Instant.parse("2026-06-09T12:00:00Z")));

        ArgumentCaptor<OutboxEventEntity> row = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(jpa).save(row.capture());
        assertThat(row.getValue().getEventId()).isEqualTo(eventId);
        assertThat(row.getValue().getAggregateType()).isEqualTo("AccountErasure");
        assertThat(row.getValue().getAggregateId()).isEqualTo(requestId);
        assertThat(row.getValue().getEventType()).isEqualTo("UserErasureAcknowledged");
        JsonNode payload = objectMapper.readTree(row.getValue().getPayload());
        assertThat(payload.get("serviceName").asText()).isEqualTo("media");
        assertThat(payload.get("authUserId").asText()).isEqualTo(user.toString());
        assertThat(payload.get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void eventIdAlreadyQueuedIsNotAppendedAgain() {
        UUID eventId = UUID.randomUUID();
        when(jpa.existsByEventId(eventId)).thenReturn(true);

        adapter.append(new UserErasureAcknowledgedEvent(
                eventId, UUID.randomUUID(), UUID.randomUUID(), "media", "SUCCESS", Instant.now()));

        verify(jpa, never()).save(any());
    }
}
