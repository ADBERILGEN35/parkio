package com.parkio.parking.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.parking.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.parking.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test: the real {@link ParkingOutboxRelay} publishes a {@code
 * ParkingSpotCreated} outbox row to a real Kafka broker (Testcontainers), keyed by the
 * spot id, with the full transport header set. Verifies relay publish, topic routing and
 * headers. Tagged {@code integration} (Docker-only; skipped otherwise).
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ParkingOutboxRelayKafkaIT {

    private static final String TOPIC = "parkio.parking.spot";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void relayPublishesParkingSpotCreatedEnvelopeKeyedBySpotId() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-06-09T12:00:00Z");
        String payload = "{\"eventId\":\"" + eventId + "\",\"parkingSpotId\":\"" + spotId
                + "\",\"ownerUserId\":\"" + ownerId + "\",\"status\":\"ACTIVE\","
                + "\"occurredAt\":\"2026-06-09T12:00:00Z\"}";
        OutboxEventEntity row = new OutboxEventEntity(UUID.randomUUID(), eventId, "ParkingSpot", spotId,
                "ParkingSpotCreated", payload, occurredAt, false);

        OutboxEventJpaRepository repo = mock(OutboxEventJpaRepository.class);
        when(repo.findUnpublishedBatchForUpdate(anyInt())).thenReturn(List.of(row));

        DefaultKafkaProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(producerProps());
        try (KafkaConsumer<String, String> consumer = stringConsumer()) {
            consumer.subscribe(List.of(TOPIC));
            consumer.poll(Duration.ofMillis(500));

            new ParkingOutboxRelay(repo, new KafkaTemplate<>(producerFactory), objectMapper, 100, 10_000L)
                    .publishPending();

            ConsumerRecord<String, String> record = pollOne(consumer);

            assertThat(record.key()).isEqualTo(spotId.toString());
            assertThat(header(record, "eventId")).isEqualTo(eventId.toString());
            assertThat(header(record, "eventType")).isEqualTo("ParkingSpotCreated");
            assertThat(header(record, "aggregateType")).isEqualTo("ParkingSpot");
            assertThat(header(record, "aggregateId")).isEqualTo(spotId.toString());
            assertThat(header(record, "version")).isEqualTo("1");

            JsonNode envelope = objectMapper.readTree(record.value());
            assertThat(envelope.get("eventType").asText()).isEqualTo("ParkingSpotCreated");
            assertThat(envelope.get("payload").get("ownerUserId").asText()).isEqualTo(ownerId.toString());
        } finally {
            producerFactory.destroy();
        }
        assertThat(row.isPublished()).isTrue();
    }

    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }

    private KafkaConsumer<String, String> stringConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer) {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("No record received on " + TOPIC + " within timeout");
    }

    private static String header(ConsumerRecord<String, String> record, String key) {
        Header h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
