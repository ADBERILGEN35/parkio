package com.parkio.auth.application.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.FrontierVersions;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.Verdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedFrontier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The frontier is read as its highest verified version; versions that fail verification are
 * ignored and counted, so a tampered version is visible to the operator without moving the
 * boundary. Synthetic key and data only.
 */
class DurableErasureFrontierVersionsTest {

    private static final String DATABASE = "auth-db:frontier-versions-test";
    private static final TrustedKey PRODUCER = TrustedKey.active("auth-frontier-versions-test-key", "auth-frontier-test",
            "frontier-versions-test-key-not-a-secret".getBytes(StandardCharsets.UTF_8),
            Instant.parse("2026-01-01T00:00:00Z"));
    private static final DurableErasureEvidenceVerifier VERIFIER = new DurableErasureEvidenceVerifier(
            new EvidenceTrust(DATABASE, List.of(PRODUCER)), Instant.parse("2026-10-03T00:00:00Z"));
    private static final byte[] FORGED = "{\"expectedThrough\":999,\"highestReserved\":999,\"kind\":\"erasure-expected-frontier\"}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void theHighestVerifiedVersionWinsAndUnverifiedVersionsAreCounted() {
        FrontierVersions read = VERIFIER.verifyFrontierVersions(List.of(
                DurableErasureEvidence.frontier(1, 1, DATABASE, PRODUCER),
                FORGED,
                DurableErasureEvidence.frontier(2, 2, DATABASE, PRODUCER),
                DurableErasureEvidence.frontier(1, 2, DATABASE, PRODUCER)));

        assertThat(read.highest()).contains(new VerifiedFrontier(2, 2, PRODUCER.producerId()));
        assertThat(read.ignoredVersions()).isOne();
    }

    @Test
    void withoutAnyVerifiedVersionTheFirstFailureIsThrown() {
        assertThatThrownBy(() -> VERIFIER.verifyFrontierVersions(List.of(FORGED, FORGED)))
                .isInstanceOf(DurableEvidenceException.class);
        assertThat(VERIFIER.verifyFrontierVersions(List.of()).highest()).isEmpty();
        assertThat(VERIFIER.verifyFrontierVersions(List.of()).ignoredVersions()).isZero();
    }

    @Test
    void recoveryReportsTheIgnoredFrontierVersions() {
        RecoveryVerdict verdict = VERIFIER.recover(new FrontierOnly(List.of(
                DurableErasureEvidence.frontier(0, 0, DATABASE, PRODUCER), FORGED, FORGED)), null);

        assertThat(verdict.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(verdict.expectedThrough()).isZero();
        assertThat(verdict.ignoredFrontierVersions()).isEqualTo(2);
    }

    /** A versioned store that holds nothing but frontier versions. */
    private record FrontierOnly(List<byte[]> frontierVersions) implements EvidenceObjects {

        @Override
        public List<String> list(String prefix) {
            return List.of();
        }

        @Override
        public Optional<byte[]> find(String key) {
            return Optional.empty();
        }

        @Override
        public List<byte[]> findAll(String key) {
            return DurableErasureEvidence.FRONTIER_KEY.equals(key) ? frontierVersions : List.of();
        }
    }
}
