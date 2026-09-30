package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.infrastructure.messaging.MediaOutboxRelay;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import com.parkio.media.infrastructure.persistence.mapper.MediaPersistenceMapper;
import com.parkio.media.infrastructure.storage.MediaStorageException;
import com.parkio.media.infrastructure.storage.MinioMediaStorageAdapter;
import com.parkio.media.application.port.ErasureAckOutbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * U05 media erasure against real PostgreSQL (Flyway), a real disposable MinIO and a real Kafka
 * broker, with synthetic objects only. SUCCESS is reported only after the metadata erase
 * committed and every stored object of the user is confirmed deleted (or already absent); a
 * storage outage, a single failing object or a crash between phases leaves the job pending with no
 * SUCCESS, and a later worker attempt completes it (docs/architecture/erasure-ack-outbox-contract.md).
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MediaAccountErasurePostgresMinioIT {

    private static final String ERASURE_TOPIC = "parkio.privacy.erasure";
    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-media-erasure-it";
    private static final byte[] CONTENT = "synthetic-object".getBytes();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(
                    // RELEASE.2024-09-13T20-26-02Z; same publisher digest as CI Compose.
                    "ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c"))
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.media.storage.bucket", () -> BUCKET);
        registry.add("parkio.media.storage.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("parkio.media.storage.public-endpoint", () -> "http://localhost:" + MINIO.getMappedPort(9000));
        registry.add("parkio.media.storage.access-key", () -> ACCESS_KEY);
        registry.add("parkio.media.storage.secret-key", () -> SECRET_KEY);
        registry.add("parkio.media.storage.region", () -> "us-east-1");
        // A paused MinIO must fail fast, like a real outage seen by a bounded client.
        registry.add("parkio.media.storage.connect-timeout", () -> "2s");
        registry.add("parkio.media.storage.read-timeout", () -> "2s");
        registry.add("parkio.media.storage.write-timeout", () -> "2s");
        registry.add("parkio.media.storage.call-timeout", () -> "4s");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired private AccountErasureHandler handler;
    @Autowired private MediaObjectErasureWorker worker;
    @Autowired private MediaErasureJobStore jobs;
    @Autowired private ErasureAckOutbox ackOutbox;
    @Autowired private MediaFileJpaRepository mediaFiles;
    @Autowired private OutboxEventJpaRepository outbox;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("internalMinioClient") private MinioClient minio;
    @SpyBean private MinioMediaStorageAdapter storage;

    @BeforeEach
    void bucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void commitFailureDeletesNoObjectAndQueuesNoAck() {
        UUID user = UUID.randomUUID();
        String key = storeObject(user);
        UserErasureRequestedEvent event = request(user);
        installCommitFailure();
        try {
            assertThatThrownBy(() -> handler.handle(event));
        } finally {
            dropCommitFailure();
        }

        assertThat(objectExists(key)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ? AND status = 'DELETED'", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM media_erasure_jobs WHERE auth_user_id = ?", user)).isZero();
        assertThat(ackRows(event.erasureRequestId())).isZero();
        relay(liveBroker()).run();
        assertThat(records(ERASURE_TOPIC, event.erasureRequestId(), Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void successAckIsQueuedOnlyAfterEveryObjectIsDeletedAndUnrelatedObjectsSurvive() throws Exception {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        String first = storeObject(user);
        String second = storeObject(user);
        String alreadyDeletedByUser = storeObject(user);
        softDeleteKeepingObject(alreadyDeletedByUser); // an earlier best-effort user delete left the object behind
        String unrelated = storeObject(bystander);
        UserErasureRequestedEvent event = request(user);

        handler.handle(event);

        assertThat(objectExists(first)).isFalse();
        assertThat(objectExists(second)).isFalse();
        assertThat(objectExists(alreadyDeletedByUser)).isFalse();
        assertThat(objectExists(unrelated)).isTrue();
        assertErased(user);
        assertThat(count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ? AND status <> 'DELETED'", bystander))
                .isEqualTo(1);
        assertThat(jobStatus(event)).isEqualTo(MediaErasureJobStore.ACK_QUEUED);
        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);

        relay(liveBroker()).run();
        List<ConsumerRecord<String, String>> records = records(ERASURE_TOPIC, event.erasureRequestId(), Duration.ofSeconds(20));
        assertThat(records).hasSize(1);
        JsonNode payload = objectMapper.readTree(records.get(0).value()).get("payload");
        assertThat(payload.get("eventId").asText()).isEqualTo(AccountErasureHandler.ackEventId(event).toString());
        assertThat(payload.get("serviceName").asText()).isEqualTo("media");
        assertThat(payload.get("authUserId").asText()).isEqualTo(user.toString());
        assertThat(payload.get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void storageOutagePreventsSuccessAndALaterAttemptCompletes() {
        UUID user = UUID.randomUUID();
        String key = storeObject(user);
        UserErasureRequestedEvent event = request(user);
        MINIO.getDockerClient().pauseContainerCmd(MINIO.getContainerId()).exec();
        try {
            handler.handle(event); // metadata commits; the object phase fails against the paused store
        } finally {
            MINIO.getDockerClient().unpauseContainerCmd(MINIO.getContainerId()).exec();
        }

        assertThat(jobStatus(event)).isEqualTo(MediaErasureJobStore.PENDING);
        assertThat(jobAttempts(event)).isEqualTo(1);
        assertThat(ackRows(event.erasureRequestId())).isZero();
        assertThat(objectExists(key)).isTrue();

        assertThat(worker.process(AccountErasureHandler.ackEventId(event)))
                .isEqualTo(MediaObjectErasureWorker.Outcome.ACK_QUEUED);
        assertThat(objectExists(key)).isFalse();
        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
    }

    @Test
    void partialDeletionIsNeverReportedAsComplete() {
        UUID user = UUID.randomUUID();
        String deletable = storeObject(user);
        String failing = storeObject(user);
        UserErasureRequestedEvent event = request(user);
        doThrow(new MediaStorageException("injected delete failure", new IllegalStateException()))
                .when(storage).delete(eq(failing));

        handler.handle(event);

        assertThat(objectExists(deletable)).isFalse();
        assertThat(objectExists(failing)).isTrue();
        assertThat(jobStatus(event)).isEqualTo(MediaErasureJobStore.PENDING);
        assertThat(ackRows(event.erasureRequestId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT last_error FROM media_erasure_jobs WHERE ack_event_id = ?", String.class,
                AccountErasureHandler.ackEventId(event))).contains("1 object(s) not deleted");

        doCallRealMethod().when(storage).delete(eq(failing));
        assertThat(worker.process(AccountErasureHandler.ackEventId(event)))
                .isEqualTo(MediaObjectErasureWorker.Outcome.ACK_QUEUED);
        assertThat(objectExists(failing)).isFalse();
        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
    }

    @Test
    void restartedWorkerResumesFromDurableStateAfterACrashBetweenPhases() {
        UUID user = UUID.randomUUID();
        String key = storeObject(user);
        UserErasureRequestedEvent event = request(user);
        // "Crash" during the object phase: the process dies before any object is confirmed.
        doThrow(new MediaStorageException("process killed", new IllegalStateException()))
                .when(storage).delete(eq(key));
        handler.handle(event);
        doCallRealMethod().when(storage).delete(eq(key));
        jdbc.update("UPDATE media_erasure_jobs SET next_attempt_at = now() - interval '1 minute' WHERE ack_event_id = ?",
                AccountErasureHandler.ackEventId(event));

        // A fresh worker instance (restart) with the scheduled poll enabled picks the due job up.
        MediaObjectErasureWorker restarted = new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactionManager,
                Clock.systemUTC(), new SimpleMeterRegistry(), true, 20, 120_000, 5_000, 900_000);
        restarted.processDue();

        assertThat(objectExists(key)).isFalse();
        assertThat(jobStatus(event)).isEqualTo(MediaErasureJobStore.ACK_QUEUED);
        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
    }

    @Test
    void alreadyAbsentObjectCountsAsDeleted() throws Exception {
        UUID user = UUID.randomUUID();
        String key = storeObject(user);
        minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(key).build());
        UserErasureRequestedEvent event = request(user);

        handler.handle(event);

        assertErased(user);
        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
    }

    @Test
    void duplicateDeliveryKeepsOneJobAndOneAck() {
        UUID user = UUID.randomUUID();
        String key = storeObject(user);
        UserErasureRequestedEvent event = request(user);

        handler.handle(event);
        handler.handle(event);

        assertThat(objectExists(key)).isFalse();
        assertErased(user);
        assertThat(count("SELECT COUNT(*) FROM media_erasure_jobs WHERE auth_user_id = ?", user)).isEqualTo(1);
        assertThat(jobStatus(event)).isEqualTo(MediaErasureJobStore.ACK_QUEUED);
        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
    }

    @Test
    void redeliveryErasesMediaRecreatedAfterAnEarlierSuccess() {
        UUID user = UUID.randomUUID();
        storeObject(user);
        UserErasureRequestedEvent event = request(user);
        handler.handle(event);
        String recreated = storeObject(user); // synthetic: user-owned media reappears after the first ACK

        handler.handle(event);

        assertThat(objectExists(recreated)).isFalse();
        assertErased(user);
        assertThat(jobStatus(event)).isEqualTo(MediaErasureJobStore.ACK_QUEUED);
        assertThat(ackRows(event.erasureRequestId())).isEqualTo(1);
    }

    @Test
    void coordinatorReplayWithNewRequestEventQueuesFreshAck() {
        UUID user = UUID.randomUUID();
        storeObject(user);
        UserErasureRequestedEvent event = request(user);
        UserErasureRequestedEvent replay = new UserErasureRequestedEvent(
                UUID.randomUUID(), event.erasureRequestId(), user, Instant.now());

        handler.handle(event);
        handler.handle(replay);

        assertThat(jdbc.queryForList(
                "SELECT event_id FROM outbox_events WHERE aggregate_type = 'AccountErasure' AND aggregate_id = ?",
                UUID.class, event.erasureRequestId()))
                .containsExactlyInAnyOrder(AccountErasureHandler.ackEventId(event), AccountErasureHandler.ackEventId(replay));
    }

    @Test
    void existingMediaEventsStillRouteToTheMediaTopic() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, event_id, aggregate_type, aggregate_id, event_type, payload, occurred_at, published)
                VALUES (?, ?, 'Media', ?, 'MediaUploaded', ?, now(), false)
                """, UUID.randomUUID(), eventId, aggregateId, "{\"eventId\":\"" + eventId + "\"}");
        UUID user = UUID.randomUUID();
        UserErasureRequestedEvent event = request(user);
        handler.handle(event);

        relay(liveBroker()).run();

        assertThat(records("parkio.media.media", aggregateId, Duration.ofSeconds(20))).hasSize(1);
        assertThat(records(ERASURE_TOPIC, aggregateId, Duration.ofSeconds(3))).isEmpty();
        assertThat(records(ERASURE_TOPIC, event.erasureRequestId(), Duration.ofSeconds(20))).hasSize(1);
    }

    @Test
    void noCopyOfTheErasedUserIdRemainsOutsideDocumentedRetention() {
        UUID user = UUID.randomUUID();
        storeObject(user);
        jdbc.update("""
                INSERT INTO idempotency_records (id, user_id, http_method, operation_path, idempotency_key,
                    request_fingerprint, status, created_at, expires_at)
                VALUES (?, ?, 'POST', '/api/v1/media', ?, 'fp', 'COMPLETED', now(), now() + interval '1 day')
                """, UUID.randomUUID(), user, "key-" + UUID.randomUUID());

        handler.handle(request(user));

        List<String> hits = new ArrayList<>();
        for (Map<String, Object> column : jdbc.queryForList("""
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND data_type IN ('uuid', 'text', 'character varying', 'json', 'jsonb')
                  AND table_name NOT IN ('erased_user_tombstones', 'outbox_events', 'media_erasure_jobs',
                      'flyway_schema_history')
                  AND NOT (table_name = 'media_files' AND column_name = 'owner_user_id')
                """)) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            long rows = count("SELECT COUNT(*) FROM \"" + table + "\" WHERE strpos(\"" + name + "\"::text, ?) > 0",
                    user.toString());
            if (rows > 0) {
                hits.add(table + "." + name + "=" + rows);
            }
        }
        assertThat(hits).isEmpty();
    }

    private void assertErased(UUID user) {
        assertThat(count("SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ? AND status <> 'DELETED'", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ? AND object_deleted_at IS NULL", user))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE user_id = ?", user)).isZero();
    }

    private String storeObject(UUID owner) {
        String key = "erasure-it/" + UUID.randomUUID() + ".bin";
        storage.store(key, CONTENT, "application/octet-stream");
        Instant now = Instant.now();
        MediaFile media = MediaFile.create(owner, BUCKET, key, "image/png", CONTENT.length,
                UUID.randomUUID().toString(), null, null, now);
        media.markReady(now);
        mediaFiles.save(MediaPersistenceMapper.toEntity(media));
        return key;
    }

    private void softDeleteKeepingObject(String key) {
        jdbc.update("UPDATE media_files SET status = 'DELETED', deleted_at = now() WHERE object_key = ?", key);
    }

    private boolean objectExists(String key) {
        try {
            minio.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String jobStatus(UserErasureRequestedEvent event) {
        return jdbc.queryForObject("SELECT status FROM media_erasure_jobs WHERE ack_event_id = ?", String.class,
                AccountErasureHandler.ackEventId(event));
    }

    private int jobAttempts(UserErasureRequestedEvent event) {
        return jdbc.queryForObject("SELECT attempts FROM media_erasure_jobs WHERE ack_event_id = ?", Integer.class,
                AccountErasureHandler.ackEventId(event));
    }

    /** Deferred constraint trigger: the metadata transaction fails at COMMIT. */
    private void installCommitFailure() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION u05_fail_commit() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'u05 injected commit failure'; END $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER u05_fail_commit AFTER INSERT ON erased_user_tombstones
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION u05_fail_commit()
                """);
    }

    private void dropCommitFailure() {
        jdbc.execute("DROP TRIGGER u05_fail_commit ON erased_user_tombstones");
    }

    private static UserErasureRequestedEvent request(UUID user) {
        return new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), user, Instant.now());
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long ackRows(UUID requestId) {
        return count("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND event_type = 'UserErasureAcknowledged' AND aggregate_id = ?
                """, requestId);
    }

    /** A relay instance whose poll runs inside a transaction, as the scheduled bean's does. */
    private Runnable relay(KafkaTemplate<String, Object> template) {
        MediaOutboxRelay relay = new MediaOutboxRelay(outbox, template, objectMapper, new SimpleMeterRegistry(), 100, 5000L, 10);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return () -> tx.executeWithoutResult(status -> relay.publishPending());
    }

    private static KafkaTemplate<String, Object> liveBroker() {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 30_000,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private static List<ConsumerRecord<String, String>> records(String topic, UUID key, Duration wait) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "u05-media-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.toString().equals(record.key())) {
                        matches.add(record);
                    }
                }
                if (!matches.isEmpty() && consumer.assignment().stream()
                        .allMatch(tp -> consumer.position(tp) >= consumer.endOffsets(List.of(tp)).get(tp))) {
                    break;
                }
            }
        }
        return matches;
    }
}
