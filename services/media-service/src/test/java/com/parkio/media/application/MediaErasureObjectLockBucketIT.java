package com.parkio.media.application;

import static com.parkio.media.application.MediaErasureFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.media.application.MediaErasureFixture.Seeded;
import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.MediaValidationResultRepository;
import com.parkio.media.application.port.OutboxEventAppender;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetObjectRetentionArgs;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * U05 media erasure on an object-lock (WORM) bucket, a topology the erasure cannot satisfy while a
 * retention period holds: the store accepts a key-only delete by adding a delete marker, yet the
 * retained version keeps its bytes. Such an object must never be reported erased; it stays pending
 * with the store's refusal recorded, and the user's unretained objects are still removed. Real
 * PostgreSQL (Flyway) and MinIO, synthetic objects; runs unchanged before and after the review fix
 * (failing before).
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MediaErasureObjectLockBucketIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-media-locked-it";

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.media.storage.bucket", () -> BUCKET);
        registry.add("parkio.media.storage.endpoint", MediaErasureObjectLockBucketIT::minioEndpoint);
        registry.add("parkio.media.storage.public-endpoint", () -> "http://localhost:" + MINIO.getMappedPort(9000));
        registry.add("parkio.media.storage.access-key", () -> ACCESS_KEY);
        registry.add("parkio.media.storage.secret-key", () -> SECRET_KEY);
        registry.add("parkio.media.storage.region", () -> "us-east-1");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired private AccountErasureHandler handler;
    @Autowired private MediaFileJpaRepository mediaFiles;
    @Autowired private MediaValidationResultRepository validationResults;
    @Autowired private OutboxEventAppender mediaEvents;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private final MinioClient minio = MediaErasureFixture.minioClient(minioEndpoint(), ACCESS_KEY, SECRET_KEY);
    private MediaErasureFixture fixture;

    @BeforeAll
    static void lockedBucket() throws Exception {
        MediaErasureFixture.minioClient(minioEndpoint(), ACCESS_KEY, SECRET_KEY)
                .makeBucket(MakeBucketArgs.builder().bucket(BUCKET).objectLock(true).build());
    }

    @BeforeEach
    void setUp() {
        fixture = new MediaErasureFixture(minio, jdbc, transactionManager, mediaFiles, validationResults, mediaEvents);
    }

    @Test
    void retainedVersionIsNeverReportedErasedWhileUnretainedObjectsAreRemoved() throws Exception {
        UUID user = UUID.randomUUID();
        Seeded retained = fixture.upload(user, BUCKET);
        minio.setObjectRetention(SetObjectRetentionArgs.builder().bucket(BUCKET).object(retained.key())
                .config(new Retention(RetentionMode.GOVERNANCE, ZonedDateTime.now().plusDays(1))).build());
        Seeded unretained = fixture.upload(user, BUCKET);
        UserErasureRequestedEvent event = request(user);

        handler.handle(event);

        assertThat(fixture.ackRows(event)).isZero();
        assertThat(fixture.storedVersions(BUCKET, retained.key())).anyMatch(v -> v.startsWith("version:"));
        assertThat(fixture.storedVersions(BUCKET, unretained.key())).isEmpty();
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT attempts, last_error FROM media_erasure_jobs WHERE erasure_request_id = ?",
                event.erasureRequestId());
        assertThat((Integer) job.get("attempts")).isEqualTo(1);
        assertThat((String) job.get("last_error")).contains(retained.mediaId().toString());
        assertThat(fixture.count("SELECT COUNT(*) FROM media_files WHERE id = ?", retained.mediaId())).isEqualTo(1);
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
