package com.parkio.media.application;

import com.parkio.media.application.command.UploadMediaCommand;
import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.result.MediaUploadResult;
import com.parkio.media.infrastructure.idempotency.IdempotencyService;
import com.parkio.media.infrastructure.idempotency.IdempotentResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Shared set-up of the delayed object write regressions (U05): real PostgreSQL (Flyway) and MinIO,
 * the production upload transaction ({@code IdempotencyService.execute} → upload, as the
 * controller calls it), the erasure handler and worker. The service reaches MinIO only through a
 * {@link DelayingStorageRelay}; the tests read MinIO directly, after any held request completed.
 * Uses only APIs that exist before the object write ledger, so the tests run unchanged on both sides.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
abstract class DelayedObjectWriteITSupport {

    static final String ACCESS_KEY = "parkio-test";
    static final String SECRET_KEY = "parkio-test-secret";

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

    private static DelayingStorageRelay relay;

    @DynamicPropertySource
    static void delayedWriteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.media.storage.endpoint", () -> "http://localhost:" + relay().port());
        registry.add("parkio.media.storage.public-endpoint", DelayedObjectWriteITSupport::minioEndpoint);
        registry.add("parkio.media.storage.access-key", () -> ACCESS_KEY);
        registry.add("parkio.media.storage.secret-key", () -> SECRET_KEY);
        registry.add("parkio.media.storage.region", () -> "us-east-1");
        registry.add("parkio.media.storage.connect-timeout", () -> "2s");
        registry.add("parkio.media.storage.read-timeout", () -> "2s");
        registry.add("parkio.media.storage.write-timeout", () -> "2s");
        registry.add("parkio.media.storage.call-timeout", () -> "2s");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired AccountErasureHandler handler;
    @Autowired MediaObjectErasureWorker worker;
    @Autowired MediaApplicationService uploads;
    @Autowired IdempotencyService idempotency;
    @Autowired JdbcTemplate jdbc;

    final MinioClient direct = MediaErasureFixture.minioClient(minioEndpoint(), ACCESS_KEY, SECRET_KEY);

    abstract String bucket();

    static synchronized DelayingStorageRelay relay() {
        if (relay == null || relay.upstreamPort() != MINIO.getMappedPort(9000)) {
            try {
                relay = new DelayingStorageRelay(MINIO.getHost(), MINIO.getMappedPort(9000));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return relay;
    }

    String namespaceFragment(UUID owner) {
        return "/" + bucket() + "/" + MediaApplicationService.objectKeyPrefix(owner);
    }

    /** The production upload transaction; returns its failure, or null if it committed. */
    Throwable upload(UUID owner) {
        try {
            uploadOk(owner);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    MediaUploadResult uploadOk(UUID owner) throws IOException {
        UploadMediaCommand command = new UploadMediaCommand(owner, "image/png", png());
        return idempotency.execute(owner, "POST", "/api/v1/media/upload", "delayed-" + UUID.randomUUID(),
                "delayed-fingerprint", MediaUploadResult.class,
                () -> IdempotentResponse.first(201, uploads.upload(command))).body();
    }

    static UserErasureRequestedEvent request(UUID owner) {
        return new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, Instant.now());
    }

    /** Later attempts of the same job, as the scheduled poll would run them. */
    void retry(UserErasureRequestedEvent event, int attempts) {
        for (int i = 0; i < attempts && ackRows(event) == 0; i++) {
            worker.process(AccountErasureHandler.ackEventId(event));
        }
    }

    long ackRows(UserErasureRequestedEvent event) {
        return count("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND event_type = 'UserErasureAcknowledged' AND aggregate_id = ?
                """, event.erasureRequestId());
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    /** Every stored version and delete marker under the owner's key namespace, read directly from MinIO. */
    List<String> storedVersions(UUID owner) throws Exception {
        List<String> versions = new ArrayList<>();
        for (Result<Item> result : direct.listObjects(ListObjectsArgs.builder().bucket(bucket())
                .prefix(MediaApplicationService.objectKeyPrefix(owner)).includeVersions(true).recursive(true).build())) {
            Item item = result.get();
            versions.add(item.objectName() + "@" + item.versionId() + (item.isDeleteMarker() ? " (delete marker)" : ""));
        }
        return versions;
    }

    static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 16; i++) {
            image.setRGB(i, (i * 5) % 16, UUID.randomUUID().hashCode());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
