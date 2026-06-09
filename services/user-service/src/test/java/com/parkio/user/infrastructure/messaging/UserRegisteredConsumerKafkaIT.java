package com.parkio.user.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.user.application.UserApplicationService;
import com.parkio.user.application.event.UserRegisteredEvent;
import com.parkio.user.application.port.InboxEventRepository;
import com.parkio.user.application.port.OutboxEventAppender;
import com.parkio.user.application.port.PendingUserStatusEventRepository;
import com.parkio.user.application.port.UserPreferenceRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.application.port.UserTrustProfileRepository;
import com.parkio.user.application.port.UserTrustScoreHistoryRepository;
import com.parkio.user.application.port.UserVehicleProfileRepository;
import com.parkio.user.domain.PendingUserStatusEvent;
import com.parkio.user.domain.UserPreference;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.UserTrustProfile;
import com.parkio.user.domain.UserTrustScoreHistory;
import com.parkio.user.domain.UserVehicleProfile;
import com.parkio.user.domain.event.UserProfileCreatedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test: a {@code UserRegistered} transport envelope produced to a real Kafka
 * broker (Testcontainers) is consumed by the real {@link UserRegisteredKafkaConsumer},
 * deserialized into the local DTO and dispatched to the real {@link UserApplicationService}
 * (in-memory fakes), which provisions a profile exactly once. A redelivery of the same
 * record is deduplicated by the inbox. Verifies consumer receive, deserialization,
 * dispatch, manual ack and inbox idempotency. Tagged {@code integration} (Docker-only).
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class UserRegisteredConsumerKafkaIT {

    private static final String TOPIC = "parkio.auth.user";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void consumesUserRegisteredAndProvisionsProfileOnceAcrossRedelivery() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-06-09T12:00:00Z");

        FakeUserProfileRepository profiles = new FakeUserProfileRepository();
        UserApplicationService userService = new UserApplicationService(
                profiles, new FakeUserPreferenceRepository(), new FakeUserVehicleProfileRepository(),
                new FakeUserTrustProfileRepository(), new FakeUserTrustScoreHistoryRepository(),
                new FakeOutboxEventAppender(), new FakeInboxEventRepository(),
                new FakePendingUserStatusEventRepository(),
                Clock.fixed(occurredAt, ZoneOffset.UTC));
        UserRegisteredKafkaConsumer consumer = new UserRegisteredKafkaConsumer(userService, objectMapper);

        produceEnvelope(eventId, userId, "rider@parkio.example", occurredAt);

        AtomicInteger acks = new AtomicInteger();
        Acknowledgment ack = acks::incrementAndGet;
        try (KafkaConsumer<String, String> raw = stringConsumer()) {
            raw.subscribe(List.of(TOPIC));
            ConsumerRecord<String, String> record = pollOne(raw);

            // Deliver the same record twice (simulating at-least-once redelivery).
            consumer.onMessage(record, "UserRegistered", ack);
            consumer.onMessage(record, "UserRegistered", ack);
        }

        assertThat(profiles.byId).hasSize(1); // inbox idempotency: provisioned once
        UserProfile profile = profiles.findByAuthUserId(userId).orElseThrow();
        assertThat(profile.displayName()).isEqualTo("rider");
        assertThat(acks.get()).isEqualTo(2); // both deliveries acknowledged
    }

    private void produceEnvelope(UUID eventId, UUID userId, String email, Instant occurredAt) throws Exception {
        UserRegisteredEvent payload = new UserRegisteredEvent(eventId, userId, email, occurredAt);
        EventEnvelope envelope = new EventEnvelope(eventId, "UserRegistered", "AuthUser", userId,
                occurredAt, 1, null, objectMapper.valueToTree(payload));
        String value = objectMapper.writeValueAsString(envelope);

        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, null, userId.toString(), value,
                    List.of(new RecordHeader("eventType", "UserRegistered".getBytes())));
            producer.send(record).get();
        }
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

    // --- in-memory fakes (no Spring, no DB) ---

    private static final class FakeUserProfileRepository implements UserProfileRepository {
        private final Map<UUID, UserProfile> byId = new HashMap<>();

        @Override
        public UserProfile save(UserProfile profile) {
            byId.put(profile.id(), profile);
            return profile;
        }

        @Override
        public Optional<UserProfile> findByAuthUserId(UUID authUserId) {
            return byId.values().stream().filter(p -> p.authUserId().equals(authUserId)).findFirst();
        }

        @Override
        public boolean existsByAuthUserId(UUID authUserId) {
            return byId.values().stream().anyMatch(p -> p.authUserId().equals(authUserId));
        }
    }

    private static final class FakeUserPreferenceRepository implements UserPreferenceRepository {
        @Override
        public UserPreference save(UserPreference preference) {
            return preference;
        }

        @Override
        public Optional<UserPreference> findByUserProfileId(UUID userProfileId) {
            return Optional.empty();
        }
    }

    private static final class FakeUserVehicleProfileRepository implements UserVehicleProfileRepository {
        @Override
        public UserVehicleProfile save(UserVehicleProfile vehicle) {
            return vehicle;
        }

        @Override
        public Optional<UserVehicleProfile> findByUserProfileId(UUID userProfileId) {
            return Optional.empty();
        }
    }

    private static final class FakeUserTrustProfileRepository implements UserTrustProfileRepository {
        @Override
        public UserTrustProfile save(UserTrustProfile trustProfile) {
            return trustProfile;
        }

        @Override
        public Optional<UserTrustProfile> findByUserProfileId(UUID userProfileId) {
            return Optional.empty();
        }
    }

    private static final class FakeUserTrustScoreHistoryRepository implements UserTrustScoreHistoryRepository {
        @Override
        public UserTrustScoreHistory save(UserTrustScoreHistory history) {
            return history;
        }
    }

    private static final class FakeOutboxEventAppender implements OutboxEventAppender {
        @Override
        public void append(UserProfileCreatedEvent event) {
            // no-op
        }
    }

    private static final class FakeInboxEventRepository implements InboxEventRepository {
        private final List<UUID> processed = new ArrayList<>();

        @Override
        public boolean existsByEventId(UUID eventId) {
            return processed.contains(eventId);
        }

        @Override
        public void markProcessed(UUID eventId, String eventType, Instant processedAt) {
            processed.add(eventId);
        }
    }

    private static final class FakePendingUserStatusEventRepository
            implements PendingUserStatusEventRepository {
        @Override
        public void save(PendingUserStatusEvent event) {
            // no-op
        }

        @Override
        public List<PendingUserStatusEvent> findByAuthUserId(UUID authUserId) {
            return List.of();
        }

        @Override
        public void deleteByAuthUserId(UUID authUserId) {
            // no-op
        }
    }
}
