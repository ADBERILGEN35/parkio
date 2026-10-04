package com.parkio.aivalidation.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.aivalidation.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.aivalidation.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.aivalidation.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * CL-F32: a failure while building or dispatching one row in phase 1 (an unreadable payload, or a
 * {@code send()} that throws instead of returning a failed future) must fail only that row and be
 * counted toward dead-lettering. It must not roll back the batch, which would re-send rows already
 * dispatched and never count the failing row.
 */
class AiValidationOutboxRelayPhaseOneFailureTest {

    private static final int MAX_ATTEMPTS = 3;

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final AiValidationOutboxRelay relay = new AiValidationOutboxRelay(outbox, kafkaTemplate,
            new ObjectMapper().registerModule(new JavaTimeModule()), new SimpleMeterRegistry(), 100, 5000L, MAX_ATTEMPTS);

    private static OutboxEventEntity row(String payload) {
        return new OutboxEventEntity(UUID.randomUUID(), UUID.randomUUID(), UserErasureAcknowledgedEvent.AGGREGATE_TYPE, UUID.randomUUID(),
                UserErasureAcknowledgedEvent.TYPE, payload, Instant.parse("2026-10-02T12:00:00Z"), false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnreadablePayloadFailsOnlyItsRowAndIsDeadLetteredAfterMaxAttempts() {
        OutboxEventEntity first = row("{\"n\":1}");
        OutboxEventEntity poison = row("{not json");
        OutboxEventEntity last = row("{\"n\":3}");
        when(outbox.findUnpublishedBatchForUpdate(100))
                .thenReturn(List.of(first, poison, last), List.of(poison), List.of(poison));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        assertThatCode(relay::publishPending).doesNotThrowAnyException();

        assertThat(first.isPublished()).isTrue();
        assertThat(last.isPublished()).isTrue();
        assertThat(poison.isPublished()).isFalse();
        assertThat(poison.getFailureCount()).isEqualTo(1);
        assertThat(poison.isDeadLettered()).isFalse();

        relay.publishPending();
        relay.publishPending();

        assertThat(poison.getFailureCount()).isEqualTo(MAX_ATTEMPTS);
        assertThat(poison.isDeadLettered()).isTrue();
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSendThatThrowsFailsItsRowAndStopsDispatchingTheRestOfTheBatch() {
        OutboxEventEntity first = row("{\"n\":1}");
        OutboxEventEntity failing = row("{\"n\":2}");
        OutboxEventEntity notDispatched = row("{\"n\":3}");
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(first, failing, notDispatched));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null))
                .thenThrow(new KafkaException("Topic not present in metadata after 5000 ms"));

        assertThatCode(relay::publishPending).doesNotThrowAnyException();

        assertThat(first.isPublished()).as("dispatched before the failure").isTrue();
        assertThat(failing.isPublished()).isFalse();
        assertThat(failing.getFailureCount()).isEqualTo(1);
        assertThat(notDispatched.isPublished()).isFalse();
        assertThat(notDispatched.getFailureCount()).as("not attempted in this poll").isZero();
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }
}
