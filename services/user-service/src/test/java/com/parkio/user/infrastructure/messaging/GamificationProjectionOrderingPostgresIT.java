package com.parkio.user.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.user.application.UserApplicationService;
import com.parkio.user.application.event.PointsEarnedEvent;
import com.parkio.user.application.event.UserRegisteredEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * U12 / CX-F07: the gamification projection in {@code user_trust_profiles} follows each
 * gamification aggregate's version, not the arrival order. Events travel through a real broker
 * into the real {@link GamificationScoreKafkaConsumer} and PostgreSQL. A single-partition topic
 * keeps arrival order equal to publication order, so "newer, then older" is deterministic.
 * Synthetic users only.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GamificationProjectionOrderingPostgresIT {

    private static final String TOPIC = GamificationScoreKafkaConsumer.GAMIFICATION_SCORE_TOPIC;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @BeforeAll
    static void createTopic() throws Exception {
        try (Admin admin = Admin.create(
                Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Set<String> existing = admin.listTopics().names().get(60, TimeUnit.SECONDS);
            if (!existing.contains(TOPIC)) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(60, TimeUnit.SECONDS);
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired private UserApplicationService users;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    /** Listeners do not auto-start in tests; start only the gamification score consumer. */
    @BeforeEach
    void startTheGamificationConsumer() {
        for (MessageListenerContainer container : listeners.getListenerContainers()) {
            String[] topics = container.getContainerProperties().getTopics();
            if (topics != null && Arrays.asList(topics).contains(TOPIC) && !container.isRunning()) {
                container.start();
            }
        }
    }

    @Test
    void aNewerPointsSnapshotSurvivesAnOlderOneThatArrivesLater() throws Exception {
        UUID user = provisionedUser();
        consume(points(user, "PointsEarned", 20, 2L), points(user, "PointsEarned", 10, 1L));
        assertThat(projection(user).get("total_points")).isEqualTo(20L);
    }

    @Test
    void aLegitimateDeductionIsApplied() throws Exception {
        UUID user = provisionedUser();
        consume(points(user, "PointsEarned", 20, 1L), points(user, "PointsDeducted", 15, 2L));
        assertThat(projection(user).get("total_points")).isEqualTo(15L);
    }

    @Test
    void aDuplicateDeliveryIsANoOp() throws Exception {
        UUID user = provisionedUser();
        Event earned = points(user, "PointsEarned", 25, 1L);
        consume(earned, earned, points(user, "PointsDeducted", 5, 2L), earned);
        assertThat(projection(user).get("total_points")).isEqualTo(5L);
    }

    @Test
    void anOlderLevelChangeStillSetsTheLevelButNotTheTotal() throws Exception {
        UUID user = provisionedUser();
        // Version 2 crossed into level 2 at 110 points; version 3 added 10 more without a
        // level change. Version 3 arrives first.
        consume(points(user, "PointsEarned", 120, 3L), level(user, 1, 2, 110, 2L));
        Map<String, Object> row = projection(user);
        assertThat(row.get("total_points")).isEqualTo(120L);
        assertThat(row.get("current_level")).isEqualTo(2);
    }

    @Test
    void aNewerTrustScoreSurvivesAnOlderOne() throws Exception {
        UUID user = provisionedUser();
        consume(trust(user, 90, 80, 5L), trust(user, 100, 90, 4L));
        assertThat(projection(user).get("trust_score")).isEqualTo(80);
    }

    @Test
    void aVersionlessLegacyEventAppliesOnlyBeforeAnyVersionedSnapshot() throws Exception {
        UUID legacyOnly = provisionedUser();
        consume(points(legacyOnly, "PointsEarned", 7, null));
        assertThat(projection(legacyOnly).get("total_points")).isEqualTo(7L);

        UUID versioned = provisionedUser();
        consume(points(versioned, "PointsEarned", 30, 1L), points(versioned, "PointsEarned", 5, null));
        assertThat(projection(versioned).get("total_points")).isEqualTo(30L);
    }

    @Test
    void aRedrivenLegacyLevelChangeDoesNotUndoAVersionedPointsSnapshot() throws Exception {
        UUID user = provisionedUser();
        // Pre-U12 history reached level 3; then a versioned points event without a level change;
        // then an older pre-U12 level change is redriven from the DLT (#217 review B1).
        consume(level(user, 2, 3, 300, null), points(user, "PointsEarned", 310, 9L), level(user, 1, 2, 110, null));
        Map<String, Object> row = projection(user);
        assertThat(row.get("current_level")).isEqualTo(3);
        assertThat(row.get("total_points")).isEqualTo(310L);
    }

    @Test
    void aRedrivenOldEventDoesNotRegressTheProjection() throws Exception {
        UUID user = provisionedUser();
        Event first = points(user, "PointsEarned", 40, 1L);
        Event failedThenRedriven = points(user, "PointsEarned", 45, 2L);
        Event latest = points(user, "PointsDeducted", 35, 3L);
        consume(first, latest);
        // A redrive republishes the original records: one never processed, one already processed.
        consume(failedThenRedriven, first);
        assertThat(projection(user).get("total_points")).isEqualTo(35L);
    }

    @Test
    void concurrentVersionsResolveToTheHighest() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                UUID user = provisionedUser();
                PointsEarnedEvent newer = objectMapper.readValue(points(user, "PointsEarned", 70, 7L).payload(),
                        PointsEarnedEvent.class);
                PointsEarnedEvent older = objectMapper.readValue(points(user, "PointsEarned", 60, 6L).payload(),
                        PointsEarnedEvent.class);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> results = new ArrayList<>();
                for (PointsEarnedEvent event : List.of(newer, older)) {
                    results.add(pool.submit((Callable<Void>) () -> {
                        start.await();
                        applyWithOneRedelivery(event);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> result : results) {
                    result.get(60, TimeUnit.SECONDS);
                }
                assertThat(projection(user).get("total_points")).as("round %d", round).isEqualTo(70L);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    /** One gamification event: its id, type, and payload JSON. */
    private record Event(UUID eventId, String type, String payload) {
    }

    /** The listener retries a failed record; here one retry stands in for that redelivery. */
    private void applyWithOneRedelivery(PointsEarnedEvent event) {
        try {
            users.handlePointsEarned(event);
        } catch (RuntimeException firstAttempt) {
            users.handlePointsEarned(event);
        }
    }

    private UUID provisionedUser() {
        UUID user = UUID.randomUUID();
        users.handleUserRegistered(new UserRegisteredEvent(UUID.randomUUID(), user,
                "synthetic-" + user + "@parkio.test", Instant.now()));
        return user;
    }

    private Map<String, Object> projection(UUID authUser) {
        return jdbc.queryForMap("""
                SELECT t.total_points, t.current_level, t.trust_score
                FROM user_trust_profiles t JOIN user_profiles p ON p.id = t.user_profile_id
                WHERE p.auth_user_id = ?""", authUser);
    }

    private Event points(UUID user, String type, long totalPoints, Long version) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("points", 5);
        payload.put("sourceType", "PARKING_SPOT_CREATED");
        payload.put("totalPoints", totalPoints);
        payload.put("relatedEventId", null);
        return event(user, type, payload, version);
    }

    private Event level(UUID user, int previousLevel, int newLevel, long totalPoints, Long version) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previousLevel", previousLevel);
        payload.put("newLevel", newLevel);
        payload.put("totalPoints", totalPoints);
        return event(user, "UserLevelChanged", payload, version);
    }

    private Event trust(UUID user, int previousScore, int newScore, Long version) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previousScore", previousScore);
        payload.put("newScore", newScore);
        payload.put("reason", "SPOT_VERIFIED");
        payload.put("relatedEventId", null);
        return event(user, "TrustScoreUpdated", payload, version);
    }

    private Event event(UUID user, String type, Map<String, Object> fields, Long version) throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("userId", user);
        payload.putAll(fields);
        payload.put("occurredAt", Instant.now().toString());
        if (version != null) {
            payload.put("aggregateVersion", version);
        }
        return new Event(eventId, type, objectMapper.writeValueAsString(payload));
    }

    /** Publishes the events in order and waits until the consumer has claimed each of them. */
    private void consume(Event... events) throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (Event event : events) {
                String key = objectMapper.readTree(event.payload()).get("userId").asText();
                String envelope = "{\"eventId\":\"" + event.eventId() + "\",\"eventType\":\"" + event.type()
                        + "\",\"aggregateType\":\"GamificationScore\",\"aggregateId\":\"" + key
                        + "\",\"occurredAt\":\"" + Instant.now() + "\",\"version\":1,\"payload\":"
                        + event.payload() + "}";
                producer.send(new ProducerRecord<>(TOPIC, null, key, envelope,
                        List.of(new RecordHeader("eventType", event.type().getBytes(StandardCharsets.UTF_8)))))
                        .get(30, TimeUnit.SECONDS);
            }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        List<UUID> ids = Arrays.stream(events).map(Event::eventId).distinct().toList();
        while (true) {
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            Integer claimed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inbox_events WHERE id IN (" + placeholders + ")", Integer.class,
                    ids.toArray());
            if (claimed != null && claimed == ids.size()) {
                // The last record's projection commits with its inbox row; one more poll
                // interval lets a trailing duplicate be acknowledged too.
                Thread.sleep(500);
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("consumer claimed " + claimed + " of " + ids.size() + " events");
            }
            Thread.sleep(200);
        }
    }
}
