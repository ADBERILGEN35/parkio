package com.parkio.auth.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.auth.domain.event.UserRegisteredEvent;
import com.parkio.auth.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.auth.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies the outbox row carries the event's eventId in its dedicated event_id column. */
class OutboxEventAppenderAdapterTest {

    private final OutboxEventJpaRepository jpa = mock(OutboxEventJpaRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final OutboxEventAppenderAdapter adapter = new OutboxEventAppenderAdapter(jpa, objectMapper);

    @Test
    void persistsPayloadEventIdIntoEventIdColumn() {
        UUID eventId = UUID.randomUUID();
        UserRegisteredEvent event = new UserRegisteredEvent(
                eventId, UUID.randomUUID(), "rider@parkio.example", Instant.parse("2026-06-08T12:00:00Z"));

        adapter.append(event);

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(jpa).save(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }
}
