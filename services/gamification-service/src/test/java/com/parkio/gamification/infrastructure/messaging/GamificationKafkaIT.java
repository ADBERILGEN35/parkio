package com.parkio.gamification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.gamification.application.GamificationApplicationService;
import com.parkio.gamification.application.LeaderboardSettings;
import com.parkio.gamification.application.event.ParkingSpotCreatedEvent;
import com.parkio.gamification.application.port.ContributionSnapshotRepository;
import com.parkio.gamification.application.port.InboxEventRepository;
import com.parkio.gamification.application.port.LevelRuleRepository;
import com.parkio.gamification.application.port.OutboxEventAppender;
import com.parkio.gamification.application.port.PenaltyRuleRepository;
import com.parkio.gamification.application.port.PointTransactionRepository;
import com.parkio.gamification.application.port.RewardRuleRepository;
import com.parkio.gamification.application.port.UserLevelProgressRepository;
import com.parkio.gamification.domain.ContributionSnapshot;
import com.parkio.gamification.domain.LevelRule;
import com.parkio.gamification.domain.PenaltyRule;
import com.parkio.gamification.domain.PointSourceType;
import com.parkio.gamification.domain.PointTransaction;
import com.parkio.gamification.domain.RewardRule;
import com.parkio.gamification.domain.UserLevelProgress;
import com.parkio.gamification.domain.event.GamificationEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests against a real Kafka broker (Testcontainers):
 * <ol>
 *   <li>a {@code ParkingSpotCreated} envelope is consumed by the real
 *       {@link ParkingEventsKafkaConsumer} and the real {@link GamificationApplicationService}
 *       (in-memory fakes) awards the owner points <em>once</em>, even on redelivery
 *       (inbox idempotency);</li>
 *   <li>a poison (malformed JSON) record routed through the real container factory's
 *       error handler lands on {@code parkio.dlt.gamification}.</li>
 * </ol>
 * Tagged {@code integration} (Docker-only; skipped otherwise).
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class GamificationKafkaIT {

    private static final String TOPIC = "parkio.parking.spot";
    private static final String DLT = "parkio.dlt.gamification";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void parkingSpotCreatedAwardsOwnerOnceAcrossRedelivery() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-06-09T12:00:00Z");

        FakeProgressRepository progress = new FakeProgressRepository();
        FakeTransactionRepository transactions = new FakeTransactionRepository();
        GamificationApplicationService gamification = new GamificationApplicationService(
                progress, transactions, new FakeLevelRuleRepository(), new FakeRewardRuleRepository(),
                new FakePenaltyRuleRepository(), new FakeContributionSnapshotRepository(),
                new FakeInboxRepository(), new FakeOutbox(), new LeaderboardSettings(20, 100),
                Clock.fixed(occurredAt, ZoneOffset.UTC));
        ParkingEventsKafkaConsumer consumer = new ParkingEventsKafkaConsumer(gamification, objectMapper);

        String payload = objectMapper.writeValueAsString(
                new ParkingSpotCreatedEvent(eventId, UUID.randomUUID(), owner, occurredAt));
        produce(TOPIC, owner.toString(), envelope(eventId, "ParkingSpotCreated", "ParkingSpot", owner, occurredAt, payload),
                "ParkingSpotCreated");

        int[] acks = {0};
        Acknowledgment ack = () -> acks[0]++;
        try (KafkaConsumer<String, String> raw = stringConsumer()) {
            raw.subscribe(List.of(TOPIC));
            ConsumerRecord<String, String> record = pollOne(raw, TOPIC);
            consumer.onMessage(record, "ParkingSpotCreated", ack);
            consumer.onMessage(record, "ParkingSpotCreated", ack); // redelivery
        }

        assertThat(progress.byUser.get(owner).totalPoints()).isEqualTo(5); // upload reward, applied once
        assertThat(transactions.all).hasSize(1);
        assertThat(acks[0]).isEqualTo(2);
    }

    @Test
    void poisonRecordIsRoutedToDeadLetterTopic() throws Exception {
        // Build the REAL container factory (with its DLT recoverer + backoff) and drive
        // the REAL consumer; a malformed envelope fails deserialization → DLT.
        GamificationKafkaConsumerConfig config =
                new GamificationKafkaConsumerConfig(KAFKA.getBootstrapServers(), true);
        ParkingEventsKafkaConsumer consumer =
                new ParkingEventsKafkaConsumer(mock(GamificationApplicationService.class), objectMapper);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.parkingEventsKafkaListenerContainerFactory(
                        config.parkingEventsConsumerFactory(),
                        dltKafkaTemplate(),
                        new KafkaTraceRecordInterceptor(objectMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        ConcurrentMessageListenerContainer<String, String> container = factory.createContainer(TOPIC);
        container.getContainerProperties().setGroupId("parkio.gamification-dlt-it");
        container.getContainerProperties().setMessageListener(
                (AcknowledgingMessageListener<String, String>) (rec, ack) -> {
                    try {
                        consumer.onMessage(rec, header(rec, "eventType"), ack);
                    } catch (Exception e) {
                        // Surface as unchecked so the container's error handler (retry → DLT) runs.
                        throw new RuntimeException(e);
                    }
                });
        container.start();
        try {
            produce(TOPIC, UUID.randomUUID().toString(), "{ this is not valid json", "ParkingSpotCreated");

            try (KafkaConsumer<String, String> dltConsumer = stringConsumer()) {
                dltConsumer.subscribe(List.of(DLT));
                ConsumerRecord<String, String> dead = pollOne(dltConsumer, DLT);
                assertThat(dead.value()).contains("not valid json");
            }
        } finally {
            container.stop();
        }
    }

    private KafkaTemplate<Object, Object> dltKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private String envelope(UUID eventId, String eventType, String aggregateType, UUID aggregateId,
                            Instant occurredAt, String payloadJson) throws Exception {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"aggregateType\":\""
                + aggregateType + "\",\"aggregateId\":\"" + aggregateId + "\",\"occurredAt\":\""
                + occurredAt + "\",\"version\":1,\"payload\":" + payloadJson + "}";
    }

    private void produce(String topic, String key, String value, String eventType) throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, key, value,
                    List.of(new RecordHeader("eventType", eventType.getBytes(StandardCharsets.UTF_8))));
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

    private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer, String topic) {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("No record received on " + topic + " within timeout");
    }

    private static String header(ConsumerRecord<String, String> record, String key) {
        Header h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    // --- in-memory fakes (mirror the unit-test fakes; no Spring, no DB) ---

    private static final class FakeProgressRepository implements UserLevelProgressRepository {
        private final Map<UUID, UserLevelProgress> byUser = new HashMap<>();

        @Override
        public UserLevelProgress save(UserLevelProgress p) {
            byUser.put(p.userId(), p);
            return p;
        }

        @Override
        public Optional<UserLevelProgress> findByUserId(UUID userId) {
            return Optional.ofNullable(byUser.get(userId));
        }

        @Override
        public List<UserLevelProgress> findTopByPoints(int limit) {
            return byUser.values().stream()
                    .sorted(Comparator.comparingLong(UserLevelProgress::totalPoints).reversed())
                    .limit(limit).toList();
        }
    }

    private static final class FakeTransactionRepository implements PointTransactionRepository {
        private final List<PointTransaction> all = new ArrayList<>();

        @Override
        public PointTransaction save(PointTransaction transaction) {
            all.add(transaction);
            return transaction;
        }

        @Override
        public boolean existsByIdempotencyKey(String idempotencyKey) {
            return all.stream().anyMatch(t -> t.idempotencyKey().equals(idempotencyKey));
        }

        @Override
        public List<PointTransaction> findRecentByUserId(UUID userId, int limit) {
            return all.stream().filter(t -> t.userId().equals(userId)).limit(limit).toList();
        }
    }

    private static final class FakeLevelRuleRepository implements LevelRuleRepository {
        @Override
        public List<LevelRule> findAll() {
            return List.of(
                    new LevelRule(1, 0, 99L, 300, 3, 20, false, false),
                    new LevelRule(2, 100, 299L, 500, 5, 40, false, false),
                    new LevelRule(3, 300, 699L, 1000, 10, 75, false, false),
                    new LevelRule(4, 700, 1499L, 1500, 15, 150, true, false),
                    new LevelRule(5, 1500, null, 2500, 25, 300, true, true));
        }
    }

    private static final class FakeRewardRuleRepository implements RewardRuleRepository {
        private final Map<String, RewardRule> byKey = new HashMap<>();

        FakeRewardRuleRepository() {
            byKey.put("PARKING_UPLOAD_OWNER", new RewardRule("PARKING_UPLOAD_OWNER", PointSourceType.PARKING_UPLOAD, 5, null));
        }

        @Override
        public Optional<RewardRule> findByRuleKey(String ruleKey) {
            return Optional.ofNullable(byKey.get(ruleKey));
        }
    }

    private static final class FakePenaltyRuleRepository implements PenaltyRuleRepository {
        @Override
        public Optional<PenaltyRule> findByRuleKey(String ruleKey) {
            return Optional.empty();
        }
    }

    private static final class FakeContributionSnapshotRepository implements ContributionSnapshotRepository {
        @Override
        public ContributionSnapshot save(ContributionSnapshot snapshot) {
            return snapshot;
        }
    }

    private static final class FakeInboxRepository implements InboxEventRepository {
        private final Set<UUID> processed = new HashSet<>();

        @Override
        public boolean existsByEventId(UUID eventId) {
            return processed.contains(eventId);
        }

        @Override
        public void markProcessed(UUID eventId, String eventType, Instant processedAt) {
            processed.add(eventId);
        }
    }

    private static final class FakeOutbox implements OutboxEventAppender {
        private final List<GamificationEvent> events = new ArrayList<>();

        @Override
        public void append(GamificationEvent event) {
            events.add(event);
        }
    }
}
