package com.parkio.parking.infrastructure.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.parking.application.ParkingApplicationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;

class ModerationActionsKafkaConsumerTest {

    private final ParkingApplicationService parking = mock(ParkingApplicationService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneOffset.UTC);
    private final ModerationActionsKafkaConsumer consumer =
            new ModerationActionsKafkaConsumer(parking, jdbc, objectMapper, clock);

    @Test
    void appliesModeratorRejectionOnceAndAcknowledges() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        when(jdbc.update(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(eventId),
                org.mockito.ArgumentMatchers.eq("ParkingSpotRejectedByModerator"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(1, 0);
        ConsumerRecord<String, String> record = record(eventId, spotId);

        consumer.onMessage(record, "ParkingSpotRejectedByModerator", null, ack);
        consumer.onMessage(record, "ParkingSpotRejectedByModerator", null, ack);

        verify(parking).rejectSpotByModerator(spotId);
        verify(ack, org.mockito.Mockito.times(2)).acknowledge();
    }

    @Test
    void ignoresUnsupportedModerationAction() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();

        consumer.onMessage(record(eventId, spotId), "UserSuspended", null, ack);

        verify(parking, never()).rejectSpotByModerator(spotId);
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(UUID eventId, UUID spotId) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("parkingSpotId", spotId.toString());
        payload.put("ownerUserId", UUID.randomUUID().toString());
        payload.put("moderatorUserId", UUID.randomUUID().toString());
        payload.put("moderationCaseId", UUID.randomUUID().toString());
        payload.put("reason", "ILLEGAL_OR_RISKY");
        payload.put("occurredAt", "2026-06-09T12:00:00Z");

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "ParkingSpotRejectedByModerator");
        envelope.put("aggregateType", "ParkingSpot");
        envelope.put("aggregateId", spotId.toString());
        envelope.put("occurredAt", "2026-06-09T12:00:00Z");
        envelope.put("version", 1);
        envelope.set("payload", payload);
        return new ConsumerRecord<>(ModerationActionsKafkaConsumer.TOPIC, 0, 0,
                spotId.toString(), objectMapper.writeValueAsString(envelope));
    }
}
