package com.parkio.media.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.media.application.port.MediaStoragePort.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.messages.Item;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MediaInfrastructureIntegrationTest {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-media-integration";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
                    + "YAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_media_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(
                    "minio/minio:RELEASE.2024-09-13T20-26-02Z"))
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void configureInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.media.storage.bucket", () -> BUCKET);
        registry.add("parkio.media.storage.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("parkio.media.storage.access-key", () -> ACCESS_KEY);
        registry.add("parkio.media.storage.secret-key", () -> SECRET_KEY);
        registry.add("parkio.media.storage.region", () -> "us-east-1");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired
    private MinioMediaStorageAdapter storage;

    @Autowired
    private MinioClient minio;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void ensureBucketExists() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void mediaFlywayMigrationsRunCleanlyOnPostgresql() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isGreaterThanOrEqualTo(7);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'media_files'
                """,
                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void storageAdapterUploadsAndDeletesObjectAgainstMinio() throws Exception {
        String objectKey = "integration/" + UUID.randomUUID() + ".png";

        StoredObject stored = storage.store(objectKey, PNG, "image/png");

        assertThat(stored.bucket()).isEqualTo(BUCKET);
        assertThat(stored.objectKey()).isEqualTo(objectKey);
        assertThat(minio.statObject(StatObjectArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .build()))
                .satisfies(stat -> {
                    assertThat(stat.size()).isEqualTo(PNG.length);
                    assertThat(stat.contentType()).isEqualTo("image/png");
                });

        storage.delete(objectKey);

        assertThat(listObjectNames()).doesNotContain(objectKey);
    }

    private java.util.List<String> listObjectNames() throws Exception {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (var result : minio.listObjects(
                ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build())) {
            Item item = result.get();
            names.add(item.objectName());
        }
        return names;
    }
}
