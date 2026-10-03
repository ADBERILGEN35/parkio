package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.Verdict;
import com.parkio.auth.application.durable.DurableEvidenceException;
import com.parkio.auth.application.durable.ProducerKey;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import io.minio.messages.RetentionMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The frontier is rewritten, so it has many versions, and S3 lists versions newest first by
 * modification time. A backward clock step on the store host gives a later version an earlier
 * modification time, so the first listed version can be an older frontier (observed with MinIO
 * on a host whose clock steps back periodically; see the U02 frontier-order evidence). The
 * frontier must therefore be read as its highest verified version, not as the first listed one.
 * Here a client lists the frontier's versions oldest first, which is what such a step does to
 * the versions written around it. Synthetic keys and data only.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ObjectLockFrontierVersionOrderIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String DATABASE = "auth-db:frontier-order-it";
    private static final ProducerKey PRODUCER =
            new ProducerKey("auth-frontier-order-it", "frontier-order-it-key-not-a-secret".getBytes(StandardCharsets.UTF_8));
    private static final AtomicInteger BUCKETS = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();

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

    private MinioClient client;
    private String bucketName;

    @BeforeEach
    void freshLockedBucket() throws Exception {
        client = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials(ACCESS_KEY, SECRET_KEY)
                .region("us-east-1")
                .build();
        bucketName = "parkio-erasure-frontier-order-it-" + BUCKETS.incrementAndGet();
        ObjectLockTestBuckets.createLockedBucket(client, bucketName);
    }

    @Test
    void theStoreReadsTheHighestFrontierWhenVersionsAreListedOutOfOrder() {
        ObjectLockDurableErasureRecordStore store = store(new FrontierOldestFirstMinioClient(client));
        List<DurableErasureRecord> records = List.of(record(), record(), record());
        records.forEach(store::putIfAbsent);

        for (DurableErasureRecord record : records) {
            assertThat(store.findByRequestId(record.erasureRequestId())).as("durable").contains(record);
        }
        // Every version a monotonic writer produces for three puts, in any listing order: no
        // version below one the store had already written (a stale read would add (0,2), (0,3)).
        assertThat(frontierVersions()).containsExactlyInAnyOrder(
                List.of(0L, 1L), List.of(1L, 1L), List.of(1L, 2L), List.of(2L, 2L), List.of(2L, 3L), List.of(3L, 3L));
    }

    @Test
    void recoveryReadsTheHighestFrontierWhenVersionsAreListedOutOfOrder() {
        List.of(record(), record(), record()).forEach(store(client)::putIfAbsent);

        RecoveryVerdict verdict = verifier().recover(
                new ObjectLockEvidenceObjects(new ObjectLockBucket(new FrontierOldestFirstMinioClient(client), bucketName)),
                3L);

        assertThat(verdict.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(verdict.expectedThrough()).isEqualTo(3L);
        assertThat(verdict.pending()).hasSize(3);
    }

    @Test
    void aFrontierVersionThatFailsVerificationIsIgnoredWhileAValidOneExists() {
        ObjectLockDurableErasureRecordStore store = store(client);
        store.putIfAbsent(record());
        // A newer version that is not signed by a trusted key (here: claims a far higher boundary).
        bucket().put(DurableErasureEvidence.FRONTIER_KEY,
                "{\"expectedThrough\":999,\"highestReserved\":999,\"kind\":\"erasure-expected-frontier\"}"
                        .getBytes(StandardCharsets.UTF_8),
                RetentionMode.GOVERNANCE, ZonedDateTime.now(ZoneOffset.UTC).plusDays(1));

        DurableErasureRecord next = record();
        store.putIfAbsent(next);

        assertThat(store.findByRequestId(next.erasureRequestId())).contains(next);
        RecoveryVerdict verdict = verifier().recover(new ObjectLockEvidenceObjects(bucket()), 2L);
        assertThat(verdict.expectedThrough()).isEqualTo(2L);
    }

    @Test
    void aFrontierWithoutAnyVerifiedVersionStillFailsClosed() {
        bucket().put(DurableErasureEvidence.FRONTIER_KEY,
                DurableErasureEvidence.frontier(1, 1, DATABASE,
                        new ProducerKey(PRODUCER.producerId(), "another-key-not-a-secret".getBytes(StandardCharsets.UTF_8))),
                RetentionMode.GOVERNANCE, ZonedDateTime.now(ZoneOffset.UTC).plusDays(1));

        assertThatThrownBy(() -> verifier().recover(new ObjectLockEvidenceObjects(bucket()), null))
                .isInstanceOf(DurableEvidenceException.class)
                .hasMessage("frontier signature mismatch");
    }

    private ObjectLockDurableErasureRecordStore store(MinioClient minio) {
        return new ObjectLockDurableErasureRecordStore(new ObjectLockBucket(minio, bucketName), DATABASE, PRODUCER,
                RetentionMode.GOVERNANCE, Duration.ofDays(1), Clock.systemUTC());
    }

    private ObjectLockBucket bucket() {
        return new ObjectLockBucket(client, bucketName);
    }

    /** (expectedThrough, highestReserved) of every frontier version in the bucket. */
    private List<List<Long>> frontierVersions() {
        List<List<Long>> pairs = new ArrayList<>();
        try {
            for (Result<Item> result : client.listObjects(ListObjectsArgs.builder().bucket(bucketName)
                    .prefix(DurableErasureEvidence.FRONTIER_KEY).includeVersions(true).build())) {
                Item item = result.get();
                if (item.isDeleteMarker()) {
                    continue;
                }
                try (var body = client.getObject(GetObjectArgs.builder().bucket(bucketName)
                        .object(item.objectName()).versionId(item.versionId()).build())) {
                    JsonNode frontier = JSON.readTree(body.readAllBytes());
                    pairs.add(List.of(frontier.path("expectedThrough").asLong(), frontier.path("highestReserved").asLong()));
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("could not read the frontier versions", ex);
        }
        return pairs;
    }

    private static DurableErasureEvidenceVerifier verifier() {
        return new DurableErasureEvidenceVerifier(DATABASE, Map.of(PRODUCER.producerId(), PRODUCER.key()));
    }

    private static DurableErasureRecord record() {
        return DurableErasureRecord.of(UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-09-29T08:16:00Z"));
    }

    /** Lists the frontier's versions oldest first, as a backward clock step can. */
    static final class FrontierOldestFirstMinioClient extends MinioClient {

        FrontierOldestFirstMinioClient(MinioClient client) {
            super(client);
        }

        @Override
        public Iterable<Result<Item>> listObjects(ListObjectsArgs args) {
            Iterable<Result<Item>> listed = super.listObjects(args);
            if (!DurableErasureEvidence.FRONTIER_KEY.equals(args.prefix())) {
                return listed;
            }
            List<Result<Item>> versions = new ArrayList<>();
            listed.forEach(versions::add);
            Collections.reverse(versions);
            return versions;
        }
    }
}
