package com.parkio.media.application;

import static com.parkio.media.application.MediaErasureFixture.randomContent;
import static com.parkio.media.application.MediaErasureFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.media.application.MediaErasureFixture.Seeded;
import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.MediaValidationResultRepository;
import com.parkio.media.application.port.OutboxEventAppender;
import com.parkio.media.domain.MediaValidationType;
import com.parkio.media.domain.event.MediaRejectedEvent;
import com.parkio.media.infrastructure.lifecycle.RetentionCleanupJob;
import com.parkio.media.infrastructure.messaging.MediaOutboxRelay;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import com.parkio.media.shared.Checksums;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * U05 media erasure completeness on the supported storage topology (one unversioned bucket, as
 * {@code docker/docker-compose.yml} creates it), against real PostgreSQL (Flyway), MinIO and Kafka
 * with synthetic data. PRIV-001 deletes the user's media metadata and stored objects, so after
 * SUCCESS no copy of the user's id, object keys, checksums or perceptual hashes may remain in any
 * column of the schema except the tombstone, this erasure's ACK row and already published transport
 * rows, which the existing outbox retention removes. The tests drive only the handler, the worker,
 * SQL and MinIO, so they run unchanged before and after the review fix (failing before).
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MediaErasureCompletenessIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-media-completeness-it";
    private static final String OTHER_BUCKET = "parkio-media-other-it";

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
        registry.add("parkio.media.storage.endpoint", MediaErasureCompletenessIT::minioEndpoint);
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
    @Autowired private MediaRejectionRecorder rejections;
    @Autowired private MediaFileJpaRepository mediaFiles;
    @Autowired private MediaValidationResultRepository validationResults;
    @Autowired private OutboxEventAppender mediaEvents;
    @Autowired private OutboxEventJpaRepository outbox;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private final MinioClient minio = MediaErasureFixture.minioClient(minioEndpoint(), ACCESS_KEY, SECRET_KEY);
    private MediaErasureFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new MediaErasureFixture(minio, jdbc, transactionManager, mediaFiles, validationResults, mediaEvents);
        for (String bucket : List.of(BUCKET, OTHER_BUCKET)) {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        }
    }

    @Test
    void successLeavesNoUserMetadataJobStateOrStoredObjectOutsideTransportRetention() throws Exception {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        Seeded first = fixture.upload(user, BUCKET);
        Seeded second = fixture.upload(user, BUCKET);
        fixture.softDeleteKeepingObject(second);
        // A failed upload whose rollback cleanup also failed: the object exists without a row.
        String orphan = "media/" + user + "/" + UUID.randomUUID() + ".png";
        fixture.putObject(BUCKET, orphan, randomContent());
        String rejectedChecksum = Checksums.sha256Hex(randomContent());
        rejections.record(MediaRejectedEvent.of(user, MediaValidationType.DUPLICATE,
                "Duplicate normalized checksum", rejectedChecksum, Instant.now()));
        idempotencyRecord(user, first);
        Seeded other = fixture.upload(bystander, BUCKET);
        UserErasureRequestedEvent event = request(user);

        handler.handle(event);
        relay().run(); // publishes every committed row: the media events and the ACK

        List<String> needles = Stream.concat(
                        Stream.of(user.toString(), orphan, rejectedChecksum),
                        Stream.of(first, second).flatMap(s -> Stream.of(s.key(), s.checksum(), s.perceptualHash())))
                .toList();
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(fixture.storedVersions(BUCKET, first.key())).as("first object").isEmpty();
        softly.assertThat(fixture.storedVersions(BUCKET, second.key())).as("object behind a soft-deleted row").isEmpty();
        softly.assertThat(fixture.storedVersions(BUCKET, orphan)).as("orphan object without a row").isEmpty();
        softly.assertThat(fixture.objectExists(BUCKET, other.key())).as("bystander object").isTrue();
        softly.assertThat(fixture.count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", user))
                .as("media_files rows of the user").isZero();
        softly.assertThat(fixture.count("SELECT COUNT(*) FROM media_validation_results WHERE media_id IN (?, ?)",
                first.mediaId(), second.mediaId())).as("validation results of the user's media").isZero();
        softly.assertThat(fixture.count("SELECT COUNT(*) FROM idempotency_records WHERE user_id = ?", user))
                .as("idempotency records").isZero();
        softly.assertThat(fixture.count(
                "SELECT COUNT(*) FROM media_erasure_jobs WHERE auth_user_id = ? OR erasure_request_id = ?",
                user, event.erasureRequestId())).as("erasure job rows").isZero();
        softly.assertThat(fixture.count(
                "SELECT COUNT(*) FROM media_files WHERE owner_user_id = ? AND status = 'READY'", bystander))
                .as("bystander media").isEqualTo(1);
        softly.assertThat(fixture.count("SELECT COUNT(*) FROM media_validation_results WHERE media_id = ?",
                other.mediaId())).as("bystander validation results").isEqualTo(4);
        softly.assertThat(fixture.count("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND aggregate_id = ? AND published = true
                """, event.erasureRequestId())).as("published ACK rows").isEqualTo(1);
        // Every table and every uuid/text/json column, including media_files.owner_user_id and
        // media_erasure_jobs: outside the outbox only the tombstone may still hold the id.
        softly.assertThat(residueOutsideOutbox(needles)).as("residue outside the outbox")
                .isEqualTo(Map.of("erased_user_tombstones.auth_user_id", 1L));
        // Outbox rows that still mention the user are this erasure's ACK or already published
        // transport rows (MediaUploaded/MediaRejected): nothing unpublished and nothing else.
        List<Map<String, Object>> outboxHits = outboxRowsContaining(needles);
        softly.assertThat(outboxHits).as("outbox rows mentioning the user").isNotEmpty()
                .allMatch(row -> isThisErasuresAck(row, event) || isPublishedMediaTransport(row));
        softly.assertAll();

        // The existing transport retention (published outbox rows, P7D) then removes them all.
        new RetentionCleanupJob(jdbc, Clock.offset(Clock.systemUTC(), Duration.ofDays(8)), true, true,
                Duration.ofDays(7), Duration.ofDays(30), 1000).cleanupOutbox();
        assertThat(outboxRowsContaining(needles)).isEmpty();
        assertThat(residueOutsideOutbox(needles)).containsOnlyKeys("erased_user_tombstones.auth_user_id");
    }

    @Test
    void objectStoredOutsideTheConfiguredBucketIsNeverConfirmedDeleted() throws Exception {
        UUID user = UUID.randomUUID();
        Seeded foreign = fixture.upload(user, OTHER_BUCKET);
        UserErasureRequestedEvent event = request(user);

        handler.handle(event);

        // Deleting the key in the configured bucket proves nothing about the other bucket.
        assertThat(fixture.objectExists(OTHER_BUCKET, foreign.key())).isTrue();
        assertThat(fixture.ackRows(event)).isZero();
        assertThat(fixture.jobRows(event)).isEqualTo(1);
        assertThat(fixture.count("SELECT COUNT(*) FROM media_files WHERE id = ? AND status = 'DELETED'",
                foreign.mediaId())).isEqualTo(1);
    }

    @Test
    void unexpectedAttemptFailureIsRecordedOnThePendingJobWithoutSuccess() throws Exception {
        UUID user = UUID.randomUUID();
        Seeded media = fixture.upload(user, BUCKET);
        UserErasureRequestedEvent event = request(user);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION u05_fail_ack_append() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'u05 injected ACK append failure'; END $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER u05_fail_ack_append BEFORE INSERT ON outbox_events FOR EACH ROW
                WHEN (NEW.event_type = 'UserErasureAcknowledged') EXECUTE FUNCTION u05_fail_ack_append()
                """);
        try {
            handler.handle(event);
        } finally {
            jdbc.execute("DROP TRIGGER u05_fail_ack_append ON outbox_events");
        }

        assertThat(fixture.storedVersions(BUCKET, media.key())).isEmpty();
        assertThat(fixture.ackRows(event)).isZero();
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT ack_event_id, attempts, last_error FROM media_erasure_jobs WHERE erasure_request_id = ?",
                event.erasureRequestId());
        assertThat((Integer) job.get("attempts")).isEqualTo(1);
        assertThat((String) job.get("last_error")).contains("u05 injected ACK append failure");

        worker.process((UUID) job.get("ack_event_id"));

        assertThat(fixture.ackRows(event)).isEqualTo(1);
        assertThat(fixture.jobRows(event)).isZero();
    }

    @Test
    void mediaCommittedWhileTheObjectPhaseWaitsIsErasedBeforeSuccess() throws Exception {
        UUID user = UUID.randomUUID();
        Seeded early = fixture.upload(user, BUCKET);
        UserErasureRequestedEvent event = request(user);
        MINIO.getDockerClient().pauseContainerCmd(MINIO.getContainerId()).exec();
        try {
            handler.handle(event); // metadata commits; the object phase fails against the paused store
        } finally {
            MINIO.getDockerClient().unpauseContainerCmd(MINIO.getContainerId()).exec();
        }
        assertThat(fixture.ackRows(event)).isZero();
        // An upload that passed authentication before the erasure started commits afterwards.
        Seeded late = fixture.upload(user, BUCKET);

        worker.process(fixture.jobId(event));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(fixture.storedVersions(BUCKET, early.key())).as("early object").isEmpty();
        softly.assertThat(fixture.storedVersions(BUCKET, late.key())).as("late object").isEmpty();
        softly.assertThat(fixture.count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", user))
                .as("media_files rows of the user").isZero();
        softly.assertThat(fixture.ackRows(event)).as("ACK rows").isEqualTo(1);
        softly.assertAll();
    }

    private void idempotencyRecord(UUID user, Seeded media) {
        jdbc.update("""
                INSERT INTO idempotency_records (id, user_id, http_method, operation_path, idempotency_key,
                    request_fingerprint, status, response_status, response_body, created_at, expires_at)
                VALUES (?, ?, 'POST', '/api/v1/media', ?, 'fp', 'COMPLETED', 201, ?, now(), now() + interval '1 day')
                """, UUID.randomUUID(), user, "key-" + UUID.randomUUID(),
                "{\"id\":\"" + media.mediaId() + "\",\"objectKey\":\"" + media.key() + "\"}");
    }

    /** "table.column" → rows containing any needle, for every table except outbox_events and Flyway's. */
    private Map<String, Long> residueOutsideOutbox(List<String> needles) {
        Map<String, Long> hits = new TreeMap<>();
        for (Map<String, Object> column : textColumns()) {
            String table = (String) column.get("table_name");
            if ("outbox_events".equals(table)) {
                continue;
            }
            String name = (String) column.get("column_name");
            long rows = fixture.count("SELECT COUNT(*) FROM \"" + table + "\" WHERE "
                    + containsAny("\"" + name + "\"", needles), needles.toArray());
            if (rows > 0) {
                hits.put(table + "." + name, rows);
            }
        }
        return hits;
    }

    private List<Map<String, Object>> outboxRowsContaining(List<String> needles) {
        List<String> columns = textColumns().stream()
                .filter(c -> "outbox_events".equals(c.get("table_name")))
                .map(c -> "\"" + c.get("column_name") + "\"")
                .toList();
        List<Object> args = new ArrayList<>();
        columns.forEach(c -> args.addAll(needles));
        return jdbc.queryForList("SELECT aggregate_type, aggregate_id, event_type, published FROM outbox_events WHERE "
                + columns.stream().map(c -> containsAny(c, needles)).collect(Collectors.joining(" OR ")),
                args.toArray());
    }

    private List<Map<String, Object>> textColumns() {
        return jdbc.queryForList("""
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND data_type IN ('uuid', 'text', 'character varying', 'json', 'jsonb')
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name, column_name
                """);
    }

    private static String containsAny(String column, List<String> needles) {
        return needles.stream().map(n -> "strpos(" + column + "::text, ?) > 0")
                .collect(Collectors.joining(" OR ", "(", ")"));
    }

    private static boolean isThisErasuresAck(Map<String, Object> row, UserErasureRequestedEvent event) {
        return "AccountErasure".equals(row.get("aggregate_type"))
                && "UserErasureAcknowledged".equals(row.get("event_type"))
                && event.erasureRequestId().equals(row.get("aggregate_id"));
    }

    private static boolean isPublishedMediaTransport(Map<String, Object> row) {
        return "Media".equals(row.get("aggregate_type")) && Boolean.TRUE.equals(row.get("published"));
    }

    private Runnable relay() {
        MediaOutboxRelay relay = new MediaOutboxRelay(outbox, liveBroker(), objectMapper, new SimpleMeterRegistry(),
                100, 5000L, 10);
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

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
