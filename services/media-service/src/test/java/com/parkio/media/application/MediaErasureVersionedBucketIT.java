package com.parkio.media.application;

import static com.parkio.media.application.MediaErasureFixture.randomContent;
import static com.parkio.media.application.MediaErasureFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.media.application.MediaErasureFixture.Seeded;
import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.MediaValidationResultRepository;
import com.parkio.media.application.port.OutboxEventAppender;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * U05 media erasure on a versioned bucket (production-readiness recommends enabling versioning;
 * the checked-in Compose bucket is unversioned). A key-only delete there only adds a delete marker
 * and keeps every older version's bytes, so absence of the latest object proves nothing: SUCCESS
 * requires that no version and no delete marker of the user's keys is left, while another user's
 * versions survive. Real PostgreSQL (Flyway) and MinIO, synthetic objects; runs unchanged before
 * and after the review fix (failing before).
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MediaErasureVersionedBucketIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-media-versioned-it";

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
        registry.add("parkio.media.storage.endpoint", MediaErasureVersionedBucketIT::minioEndpoint);
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
    static void versionedBucket() throws Exception {
        MinioClient admin = MediaErasureFixture.minioClient(minioEndpoint(), ACCESS_KEY, SECRET_KEY);
        admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        versioning(admin, VersioningConfiguration.Status.ENABLED);
    }

    @BeforeEach
    void setUp() {
        fixture = new MediaErasureFixture(minio, jdbc, transactionManager, mediaFiles, validationResults, mediaEvents);
    }

    @Test
    @Order(1)
    void everyVersionAndDeleteMarkerOfTheUsersObjectsIsRemovedAndOtherUsersVersionsSurvive() throws Exception {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        Seeded overwritten = fixture.upload(user, BUCKET);
        fixture.putObject(BUCKET, overwritten.key(), randomContent());
        // The earlier best-effort owner delete only hid the bytes behind a delete marker.
        Seeded deletedByOwner = fixture.upload(user, BUCKET);
        minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(deletedByOwner.key()).build());
        fixture.softDeleteKeepingObject(deletedByOwner);
        Seeded other = fixture.upload(bystander, BUCKET);
        fixture.putObject(BUCKET, other.key(), randomContent());
        assertThat(fixture.storedVersions(BUCKET, overwritten.key())).hasSize(2);
        assertThat(fixture.storedVersions(BUCKET, deletedByOwner.key()))
                .hasSize(2).anyMatch(v -> v.startsWith("delete-marker:"));
        UserErasureRequestedEvent event = request(user);

        handler.handle(event);

        assertThat(fixture.storedVersions(BUCKET, overwritten.key())).isEmpty();
        assertThat(fixture.storedVersions(BUCKET, deletedByOwner.key())).isEmpty();
        assertThat(fixture.storedVersions(BUCKET, other.key())).hasSize(2);
        assertThat(fixture.count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", user)).isZero();
        assertThat(fixture.ackRows(event)).isEqualTo(1);
        assertThat(fixture.jobRows(event)).isZero();
    }

    @Test
    @Order(2)
    void suspendedVersioningLeavesNeitherTheNullVersionNorOlderVersions() throws Exception {
        UUID user = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        Seeded media = fixture.upload(user, BUCKET);
        fixture.putObject(BUCKET, media.key(), randomContent());
        Seeded other = fixture.upload(bystander, BUCKET);
        versioning(minio, VersioningConfiguration.Status.SUSPENDED);
        fixture.putObject(BUCKET, media.key(), randomContent()); // the "null" version, over two older ones
        assertThat(fixture.storedVersions(BUCKET, media.key())).hasSize(3).contains("version:null");
        UserErasureRequestedEvent event = request(user);

        handler.handle(event);

        assertThat(fixture.storedVersions(BUCKET, media.key())).isEmpty();
        assertThat(fixture.storedVersions(BUCKET, other.key())).hasSize(1);
        assertThat(fixture.ackRows(event)).isEqualTo(1);
        assertThat(fixture.jobRows(event)).isZero();
    }

    private static void versioning(MinioClient client, VersioningConfiguration.Status status) throws Exception {
        client.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET)
                .config(new VersioningConfiguration(status, null)).build());
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
