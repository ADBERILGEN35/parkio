package com.parkio.auth.application.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Key windows and retirement (format v2). The producer signs only inside
 * {@code [notBefore, notAfter)} with a key that is not retired. A verifier refuses unknown,
 * foreign and retired keys and a key used before its {@code notBefore}, but keeps accepting what
 * a key signed before its window closed: evidence is write-once and cannot be re-signed.
 */
class EvidenceTrustTest {

    private static final Instant NOT_BEFORE = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant NOT_AFTER = Instant.parse("2026-09-30T00:00:00Z");
    private static final byte[] SECRET = "evidence-trust-test-key-not-a-secret".getBytes(StandardCharsets.UTF_8);
    private static final TrustedKey KEY = new TrustedKey("key-a", "auth", SECRET, NOT_BEFORE, NOT_AFTER, false);
    private static final EvidenceTrust TRUST = new EvidenceTrust("postgresql:7000000000000000004:parkio_auth", List.of(KEY));

    @Test
    void theProducerSignsOnlyInsideTheWindowWithAKeyThatIsNotRetired() {
        assertThat(KEY.signsAt(NOT_BEFORE.minusSeconds(1))).isFalse();
        assertThat(KEY.signsAt(NOT_BEFORE)).isTrue();
        assertThat(KEY.signsAt(NOT_AFTER.minusSeconds(1))).isTrue();
        assertThat(KEY.signsAt(NOT_AFTER)).isFalse();
        assertThat(new TrustedKey("key-a", "auth", SECRET, NOT_BEFORE, NOT_AFTER, true).signsAt(NOT_BEFORE)).isFalse();
        assertThat(TrustedKey.active("key-b", "auth", SECRET, NOT_BEFORE).signsAt(Instant.parse("2099-01-01T00:00:00Z")))
                .isTrue();
    }

    @Test
    void aVerifierKeepsAcceptingAKeyAfterItsSigningWindowClosed() {
        assertThat(TRUST.verifyingKey(body("key-a", "auth"), NOT_AFTER.plusSeconds(86_400 * 365))).isEqualTo(KEY);
        assertThat(TRUST.verifyingKey(body("key-a", "auth"), NOT_BEFORE)).isEqualTo(KEY);
    }

    @Test
    void unknownForeignRetiredAndEarlyKeysAreRefused() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        assertThatThrownBy(() -> TRUST.verifyingKey(body("key-z", "auth"), now)).hasMessage("unknown producer key");
        assertThatThrownBy(() -> TRUST.verifyingKey(Map.of("producerId", "auth"), now)).hasMessage("unknown producer key");
        assertThatThrownBy(() -> TRUST.verifyingKey(body("key-a", "other"), now))
                .hasMessage("producer key belongs to another producer");
        EvidenceTrust retired = new EvidenceTrust(TRUST.databaseIdentity(),
                List.of(new TrustedKey("key-a", "auth", SECRET, NOT_BEFORE, NOT_AFTER, true)));
        assertThatThrownBy(() -> retired.verifyingKey(body("key-a", "auth"), now)).hasMessage("retired producer key");
        assertThatThrownBy(() -> TRUST.verifyingKey(body("key-a", "auth"), NOT_BEFORE.minusMillis(1)))
                .hasMessage("producer key not yet valid");
    }

    @Test
    void windowsAndTrustAreValidated() {
        assertThatThrownBy(() -> new TrustedKey("key-a", "auth", SECRET, NOT_AFTER, NOT_BEFORE, false))
                .isInstanceOf(DurableEvidenceException.class)
                .hasMessage("key key-a notAfter must be after notBefore");
        assertThatThrownBy(() -> new EvidenceTrust(" ", List.of(KEY)))
                .hasMessage("trust databaseIdentity must not be blank");
        assertThatThrownBy(() -> new EvidenceTrust(TRUST.databaseIdentity(), List.of(KEY, KEY)))
                .hasMessage("duplicate keyId key-a in trust");
    }

    @Test
    void aKeyNeverPrintsItsSecret() {
        assertThat(KEY.toString()).doesNotContain(new String(SECRET, StandardCharsets.UTF_8))
                .doesNotContain(java.util.HexFormat.of().formatHex(SECRET))
                .contains("<redacted>");
    }

    private static Map<String, Object> body(String keyId, String producerId) {
        return Map.of("keyId", keyId, "producerId", producerId);
    }
}
