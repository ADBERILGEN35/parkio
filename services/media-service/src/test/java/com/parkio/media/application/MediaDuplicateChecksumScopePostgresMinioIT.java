package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.media.application.command.UploadMediaCommand;
import com.parkio.media.application.result.MediaUploadResult;
import com.parkio.media.domain.MediaStatus;
import com.parkio.media.domain.exception.MediaErrorCode;
import com.parkio.media.domain.exception.MediaException;
import com.parkio.media.testsupport.TestImages;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * CL-F38c (owner decision B10e): duplicate detection is per owner and ignores deleted files. On
 * real PostgreSQL (Flyway) and MinIO, with synthetic images: another owner's identical file and
 * the owner's own deleted file do not block an upload; the owner's live duplicate is still
 * refused with DUPLICATE_MEDIA (409), and the database keeps at most one live file per owner and
 * checksum for uploads that pass the check concurrently.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MediaDuplicateChecksumScopePostgresMinioIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-media-duplicate-scope-it";

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
        registry.add("parkio.media.storage.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("parkio.media.storage.public-endpoint",
                () -> "http://localhost:" + MINIO.getMappedPort(9000));
        registry.add("parkio.media.storage.access-key", () -> ACCESS_KEY);
        registry.add("parkio.media.storage.secret-key", () -> SECRET_KEY);
        registry.add("parkio.media.storage.region", () -> "us-east-1");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired private MediaApplicationService media;
    @Autowired private MinioClient minio;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void ensureBucketExists() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void anotherOwnersIdenticalFileDoesNotBlockAnUpload() {
        byte[] image = TestImages.jpeg(32, 32, 0x3A7F21);
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        media.upload(new UploadMediaCommand(firstOwner, "image/jpeg", image));

        MediaUploadResult second = media.upload(new UploadMediaCommand(secondOwner, "image/jpeg", image));

        assertThat(second.status()).isEqualTo(MediaStatus.READY);
        assertThat(checksumsOf(secondOwner)).isEqualTo(checksumsOf(firstOwner)).hasSize(1);
    }

    @Test
    void anOwnerMayUploadAFileAgainAfterDeletingIt() {
        byte[] image = TestImages.jpeg(32, 32, 0x1B2C3D);
        UUID owner = UUID.randomUUID();
        MediaUploadResult first = media.upload(new UploadMediaCommand(owner, "image/jpeg", image));
        media.delete(first.mediaId(), owner);

        MediaUploadResult again = media.upload(new UploadMediaCommand(owner, "image/jpeg", image));

        assertThat(again.status()).isEqualTo(MediaStatus.READY);
        assertThat(jdbc.queryForList("SELECT status FROM media_files WHERE owner_user_id = ?", String.class, owner))
                .containsExactlyInAnyOrder("DELETED", "READY");
        assertThat(checksumsOf(owner)).hasSize(1);
    }

    @Test
    void anOwnersLiveDuplicateIsStillRefusedAndTheDatabaseKeepsOneLiveCopy() {
        byte[] image = TestImages.jpeg(32, 32, 0x5E6F70);
        UUID owner = UUID.randomUUID();
        media.upload(new UploadMediaCommand(owner, "image/jpeg", image));

        assertThatThrownBy(() -> media.upload(new UploadMediaCommand(owner, "image/jpeg", image)))
                .isInstanceOf(MediaException.class)
                .extracting(ex -> ((MediaException) ex).errorCode())
                .isEqualTo(MediaErrorCode.DUPLICATE_MEDIA);
        // Two uploads that both pass the check before either commits: the database refuses the
        // second live row for this owner and checksum.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO media_files (id, owner_user_id, bucket_name, object_key, content_type, file_size,
                                         checksum, status)
                SELECT ?, owner_user_id, bucket_name, ?, content_type, file_size, checksum, 'READY'
                FROM media_files WHERE owner_user_id = ?
                """, UUID.randomUUID(), "media/" + owner + "/concurrent.jpg", owner))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", Long.class, owner))
                .isOne();
    }

    private List<String> checksumsOf(UUID owner) {
        return jdbc.queryForList("SELECT DISTINCT checksum FROM media_files WHERE owner_user_id = ?", String.class,
                owner);
    }
}
