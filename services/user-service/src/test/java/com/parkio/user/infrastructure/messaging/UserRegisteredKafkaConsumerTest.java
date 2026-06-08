package com.parkio.user.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.user.application.UserApplicationService;
import com.parkio.user.application.event.UserRegisteredEvent;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the auth→user Kafka consumer: deserialization, dispatch and ack. */
class UserRegisteredKafkaConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UserApplicationService userService = mock(UserApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final UserRegisteredKafkaConsumer consumer =
            new UserRegisteredKafkaConsumer(userService, objectMapper);

    @Test
    void deserializesEnvelopeAndCallsHandlerThenAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ConsumerRecord<String, String> record = record(eventId, userId, "rider@parkio.example", "UserRegistered");

        consumer.onMessage(record, "UserRegistered", ack);

        ArgumentCaptor<UserRegisteredEvent> captor = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(userService).handleUserRegistered(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(eventId);
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        assertThat(captor.getValue().email()).isEqualTo("rider@parkio.example");
        verify(ack).acknowledge();
    }

    @Test
    void ignoresUnknownEventTypeButStillAcks() throws Exception {
        ConsumerRecord<String, String> record =
                record(UUID.randomUUID(), UUID.randomUUID(), "x@y.z", "SomethingElse");

        consumer.onMessage(record, "SomethingElse", ack);

        verify(userService, never()).handleUserRegistered(org.mockito.ArgumentMatchers.any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(UUID eventId, UUID userId, String email, String eventType)
            throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("userId", userId.toString());
        payload.put("email", email);
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "AuthUser");
        envelope.put("aggregateId", userId.toString());
        envelope.put("occurredAt", "2026-06-08T12:00:00Z");
        envelope.put("version", 1);
        envelope.set("payload", payload);

        return new ConsumerRecord<>("parkio.auth.user", 0, 0L,
                userId.toString(), objectMapper.writeValueAsString(envelope));
    }
}
