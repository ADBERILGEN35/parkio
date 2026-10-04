package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.Verdict;
import com.parkio.auth.application.durable.DurableEvidenceException;
import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.application.port.CapturedErasureLedger;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import io.minio.MinioClient;
import io.minio.messages.RetentionMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
 * A producer key rotation on a disposable MinIO object-lock bucket (format v2). Before the
 * rotation the store signs with the old key; afterwards a store with the new key continues the
 * same bucket (it reads the frontier the old key signed). The old key then may no longer sign,
 * and is refused before anything is reserved. A consumer trusting both keys accepts everything;
 * retiring or dropping the old key, or verifying before the new key's window, refuses it.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ObjectLockKeyRotationIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String DATABASE = "postgresql:7000000000000000007:parkio_auth";
    private static final String PRODUCER = "auth-key-rotation-it";
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

    /** Near real time: object-lock retention must lie in the bucket's future. */
    private final Instant beforeRotation = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final Instant rotation = beforeRotation.plus(Duration.ofHours(1));
    private final Instant afterRotation = beforeRotation.plus(Duration.ofHours(2));
    private final TrustedKey oldKey = new TrustedKey("auth-key-rotation-it-2026a", PRODUCER,
            "key-rotation-it-old-hmac-key-not-a-secret".getBytes(StandardCharsets.UTF_8),
            beforeRotation.minus(Duration.ofDays(1)), rotation, false);
    private final TrustedKey newKey = TrustedKey.active("auth-key-rotation-it-2026b", PRODUCER,
            "key-rotation-it-new-hmac-key-not-a-secret".getBytes(StandardCharsets.UTF_8), rotation);
    private final EvidenceTrust trust = new EvidenceTrust(DATABASE, List.of(oldKey, newKey));

    private MinioClient client;
    private ObjectLockBucket bucket;

    @BeforeEach
    void freshLockedBucket() throws Exception {
        client = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials(ACCESS_KEY, SECRET_KEY)
                .region("us-east-1")
                .build();
        String name = "parkio-erasure-key-rotation-it-" + BUCKETS.incrementAndGet();
        ObjectLockTestBuckets.createLockedBucket(client, name);
        bucket = new ObjectLockBucket(client, name);
    }

    @Test
    void aRotatedStoreContinuesTheBucketAndBothKeysVerify() {
        store(oldKey, beforeRotation).putIfAbsent(record());
        store(oldKey, beforeRotation).putIfAbsent(record());

        ObjectLockDurableErasureRecordStore rotated = store(newKey, afterRotation);
        rotated.putIfAbsent(record());
        rotated.publishCheckpoint(() -> new CapturedErasureLedger(
                List.of(new ErasureLedgerEntry(UUID.randomUUID(), beforeRotation)), afterRotation));

        assertThat(keyIdsUnder("records/")).containsExactlyInAnyOrder(oldKey.keyId(), oldKey.keyId(), newKey.keyId());
        assertThat(keyIdsUnder("checkpoints/")).containsExactly(newKey.keyId());
        assertThat(keyIdsUnder("frontier/")).containsExactly(newKey.keyId());
        RecoveryVerdict verdict = recover(trust, afterRotation.plusSeconds(3600));
        assertThat(verdict.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(verdict.expectedThrough()).isEqualTo(4L);
        assertThat(verdict.pending()).hasSize(3);
    }

    @Test
    void theOldKeyMayNotSignAfterItsWindowAndReservesNothing() {
        store(oldKey, beforeRotation).putIfAbsent(record());
        List<String> before = bucket.keys("");

        assertThatThrownBy(() -> store(oldKey, afterRotation).putIfAbsent(record()))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        assertThatThrownBy(() -> store(oldKey, afterRotation).publishCheckpoint(() -> {
            throw new AssertionError("no capture with a key that may not sign");
        })).isInstanceOf(AuthException.class);

        assertThat(bucket.keys("")).isEqualTo(before);
    }

    @Test
    void retiredUnknownAndNotYetValidKeysAreRefused() {
        store(oldKey, beforeRotation).putIfAbsent(record());
        store(newKey, afterRotation).putIfAbsent(record());
        Instant later = afterRotation.plusSeconds(3600);

        EvidenceTrust retired = new EvidenceTrust(DATABASE, List.of(new TrustedKey(oldKey.keyId(), PRODUCER,
                oldKey.key(), oldKey.notBefore(), oldKey.notAfter(), true), newKey));
        assertThatThrownBy(() -> recover(retired, later))
                .isInstanceOf(DurableEvidenceException.class).hasMessage("retired producer key");
        assertThatThrownBy(() -> recover(new EvidenceTrust(DATABASE, List.of(newKey)), later))
                .isInstanceOf(DurableEvidenceException.class).hasMessage("unknown producer key");
        assertThatThrownBy(() -> recover(trust, beforeRotation))
                .isInstanceOf(DurableEvidenceException.class).hasMessage("producer key not yet valid");
        assertThatThrownBy(() -> recover(new EvidenceTrust("postgresql:7000000000000000008:parkio_auth",
                List.of(oldKey, newKey)), later))
                .isInstanceOf(DurableEvidenceException.class).hasMessage("database identity mismatch");
    }

    private ObjectLockDurableErasureRecordStore store(TrustedKey signingKey, Instant now) {
        return new ObjectLockDurableErasureRecordStore(bucket, trust, signingKey.keyId(), RetentionMode.GOVERNANCE,
                Duration.ofDays(1), Clock.fixed(now, ZoneOffset.UTC));
    }

    private RecoveryVerdict recover(EvidenceTrust consumerTrust, Instant at) {
        return new DurableErasureEvidenceVerifier(consumerTrust, at).recover(new ObjectLockEvidenceObjects(bucket), null);
    }

    /**
     * The keyId of the canonical version of every object under {@code prefix}; for the frontier,
     * its highest verified version.
     */
    private List<String> keyIdsUnder(String prefix) {
        Set<String> keys = new TreeSet<>(bucket.keys(prefix));
        return keys.stream().map(key -> {
            try {
                byte[] canonical = DurableErasureEvidence.FRONTIER_KEY.equals(key)
                        ? ObjectLockTestBuckets.currentFrontier(bucket,
                                new DurableErasureEvidenceVerifier(trust, afterRotation.plusSeconds(3600)))
                        : bucket.oldest(key).orElseThrow().bytes();
                return JSON.readTree(canonical).path("keyId").asText();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }).toList();
    }

    private DurableErasureRecord record() {
        return DurableErasureRecord.of(UUID.randomUUID(), UUID.randomUUID(), beforeRotation);
    }
}
