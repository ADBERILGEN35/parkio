package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.media.MediaServiceApplication;
import com.parkio.media.application.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.infrastructure.config.MediaInfrastructureConfig;
import com.parkio.media.infrastructure.config.MediaProperties;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.persistence.mapper.MediaPersistenceMapper;
import com.parkio.media.infrastructure.storage.MediaStorageException;
import com.parkio.media.infrastructure.storage.MinioMediaStorageAdapter;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The isolated recovery's restored source bucket (U02, coordinator option A) on real PostgreSQL,
 * MinIO and Kafka. The isolated restore put the backup's objects into the configured (isolated)
 * bucket, while the restored rows still name the bucket they were written to. With restore replay
 * on and the source bucket mapped, the replay deletes those objects in the configured bucket and
 * media acknowledges; a row naming any other bucket is still refused and acknowledges nothing;
 * without the mapping the source bucket is refused; uploads stay in the configured bucket; and a
 * mapping with restore replay off stops the service from starting. Synthetic objects only.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class MediaRestoredSourceBucketPostgresMinioIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    /** The isolated restore's bucket ({@code <project>-media}). */
    private static final String BUCKET = "parkio-iso-0123456789ab-media";
    /** The bucket the restored rows name. */
    private static final String SOURCE = "parkio-media-restored-source";
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
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("parkio.media.storage.bucket", () -> BUCKET);
        registry.add("parkio.media.storage.restored-source-bucket", () -> SOURCE);
        registry.add("parkio.privacy.restore-replay.enabled", () -> "true");
        registry.add("parkio.media.storage.endpoint", MediaRestoredSourceBucketPostgresMinioIT::minioUrl);
        registry.add("parkio.media.storage.public-endpoint", () -> "http://localhost:" + MINIO.getMappedPort(9000));
        registry.add("parkio.media.storage.access-key", () -> ACCESS_KEY);
        registry.add("parkio.media.storage.secret-key", () -> SECRET_KEY);
        registry.add("parkio.media.storage.region", () -> "us-east-1");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired private AccountErasureHandler handler;
    @Autowired private MediaFileJpaRepository mediaFiles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MinioMediaStorageAdapter storage;
    @Autowired @Qualifier("internalMinioClient") private MinioClient minio;

    @BeforeEach
    void bucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void aRestoredRowOfTheSourceBucketIsErasedInTheIsolatedBucketAndMediaAcknowledges() throws Exception {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        String restored = restoredObject(user, SOURCE);
        String unrelated = restoredObject(bystander, SOURCE);
        UserErasureRestoreReplayRequestedEvent replay = restoreReplay(user);

        handler.replayForRestore(replay);

        assertThat(objectExists(restored)).isFalse();
        assertThat(objectExists(unrelated)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", bystander)).isEqualTo(1);
        assertThat(restoreJobRows(replay)).isZero();
        assertThat(restoreAckRows(user, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void aRowOfAnyOtherBucketIsStillRefusedAndAcknowledgesNothing() throws Exception {
        UUID user = UUID.randomUUID();
        String object = restoredObject(user, "parkio-media-third");
        UserErasureRestoreReplayRequestedEvent replay = restoreReplay(user);

        handler.replayForRestore(replay);

        assertThat(objectExists(object)).as("its deletion cannot be confirmed, so nothing is deleted").isTrue();
        assertThat(restoreAckRows(user, "SUCCESS")).isZero();
        assertThat(restoreJobRows(replay)).as("the job stays pending for a later attempt").isEqualTo(1);
    }

    @Test
    void withoutTheMappingTheSourceBucketIsRefused() {
        MinioMediaStorageAdapter unmapped = adapter(null, true);

        assertThatThrownBy(() -> unmapped.versionsOf(SOURCE, "media/" + UUID.randomUUID() + "/object.png"))
                .isInstanceOf(MediaStorageException.class)
                .hasMessageContaining("its deletion cannot be confirmed");
    }

    @Test
    void uploadsStayInTheConfiguredBucket() throws Exception {
        String key = "media/" + UUID.randomUUID() + "/upload.png";

        assertThat(storage.store(key, CONTENT, "image/png").bucket()).isEqualTo(BUCKET);
        assertThat(objectExists(key)).isTrue();
        assertThat(minio.bucketExists(BucketExistsArgs.builder().bucket(SOURCE).build())).isFalse();
        assertThat(storage.generatePresignedGetUrl(key, Duration.ofMinutes(5)))
                .contains("/" + BUCKET + "/").doesNotContain(SOURCE);
    }

    @Test
    void aMappingWithRestoreReplayOffStopsTheServiceFromStarting() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(MediaServiceApplication.class)
                .properties(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "spring.kafka.listener.auto-startup=false",
                        "server.port=0",
                        "parkio.media.storage.bucket=" + BUCKET,
                        "parkio.media.storage.restored-source-bucket=" + SOURCE,
                        "parkio.privacy.restore-replay.enabled=false",
                        "parkio.media.storage.endpoint=" + minioUrl(),
                        "parkio.media.storage.public-endpoint=" + minioUrl(),
                        "parkio.media.storage.access-key=" + ACCESS_KEY,
                        "parkio.media.storage.secret-key=" + SECRET_KEY)
                .run().close())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("parkio.media.storage.restored-source-bucket is set but parkio.privacy.restore-replay.enabled"
                        + " is false; it is honoured only during an isolated recovery");
    }

    /** A restored object: in the isolated bucket, with a row naming {@code rowBucket}. */
    private String restoredObject(UUID owner, String rowBucket) throws Exception {
        String key = "media/" + owner + "/" + UUID.randomUUID() + ".png";
        minio.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key)
                .stream(new ByteArrayInputStream(CONTENT), CONTENT.length, -1).contentType("image/png").build());
        Instant now = Instant.now();
        MediaFile media = MediaFile.create(owner, rowBucket, key, "image/png", CONTENT.length,
                UUID.randomUUID().toString(), null, null, now);
        media.markReady(now);
        mediaFiles.save(MediaPersistenceMapper.toEntity(media));
        return key;
    }

    private boolean objectExists(String key) throws Exception {
        try {
            minio.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw e;
        }
    }

    private static UserErasureRestoreReplayRequestedEvent restoreReplay(UUID user) {
        return new UserErasureRestoreReplayRequestedEvent(UUID.randomUUID(), UUID.randomUUID(),
                "backup-stamp-2026-10-06", "e".repeat(64), user, Instant.parse("2026-10-06T08:16:00Z"), Instant.now());
    }

    private long restoreJobRows(UserErasureRestoreReplayRequestedEvent replay) {
        return count("SELECT COUNT(*) FROM media_erasure_jobs WHERE ack_event_id = ?",
                AccountErasureHandler.restoreAckEventId(replay));
    }

    private long restoreAckRows(UUID user, String status) {
        return count("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND event_type = 'UserErasureRestoreAcknowledged'
                  AND aggregate_id = ? AND payload::jsonb ->> 'status' = ?
                """, user, status);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static String minioUrl() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static MinioMediaStorageAdapter adapter(String source, boolean restoreReplay) {
        MediaProperties properties = new MediaProperties();
        MediaProperties.Storage settings = properties.getStorage();
        settings.setEndpoint(minioUrl());
        settings.setPublicEndpoint(minioUrl());
        settings.setBucket(BUCKET);
        settings.setRestoredSourceBucket(source);
        settings.setAccessKey(ACCESS_KEY);
        settings.setSecretKey(SECRET_KEY);
        settings.setRegion("us-east-1");
        MediaInfrastructureConfig config = new MediaInfrastructureConfig();
        return new MinioMediaStorageAdapter(config.internalMinioClient(properties), config.presignMinioClient(properties),
                config.versionListingClient(properties), properties, 100, restoreReplay);
    }
}
