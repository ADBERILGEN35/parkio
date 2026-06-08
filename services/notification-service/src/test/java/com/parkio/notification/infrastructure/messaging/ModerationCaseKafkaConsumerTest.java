package com.parkio.notification.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.notification.application.NotificationApplicationService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the moderation.case → notification consumer: dispatch, ignore, ack. */
class ModerationCaseKafkaConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final NotificationApplicationService service = mock(NotificationApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ModerationCaseKafkaConsumer consumer = new ModerationCaseKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesAppealResolved() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("appealId", UUID.randomUUID().toString());
        payload.put("caseId", UUID.randomUUID().toString());
        payload.put("userId", UUID.randomUUID().toString());
        payload.put("accepted", true);
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record("AppealResolved", payload), "AppealResolved", ack);

        verify(service).handleAppealResolved(any());
        verify(ack).acknowledge();
    }

    @Test
    void dispatchesModerationCaseResolved() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("caseId", UUID.randomUUID().toString());
        payload.put("targetType", "USER");
        payload.put("targetId", UUID.randomUUID().toString());
        payload.put("action", "SUSPEND_USER");
        payload.put("moderatorId", UUID.randomUUID().toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record("ModerationCaseResolved", payload), "ModerationCaseResolved", ack);

        verify(service).handleModerationCaseResolved(any());
        verify(ack).acknowledge();
    }

    @Test
    void ignoresUnsupportedCaseEventButStillAcks() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("caseId", UUID.randomUUID().toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record("ModerationCaseOpened", payload), "ModerationCaseOpened", ack);

        verify(service, never()).handleAppealResolved(any());
        verify(service, never()).handleModerationCaseResolved(any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(String eventType, ObjectNode payload) throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", eventType.startsWith("Appeal") ? "Appeal" : "ModerationCase");
        envelope.put("aggregateId", UUID.randomUUID().toString());
        envelope.put("occurredAt", "2026-06-08T12:00:00Z");
        envelope.put("version", 1);
        envelope.set("payload", payload);
        return new ConsumerRecord<>("parkio.moderation.case", 0, 0L,
                UUID.randomUUID().toString(), objectMapper.writeValueAsString(envelope));
    }
}
