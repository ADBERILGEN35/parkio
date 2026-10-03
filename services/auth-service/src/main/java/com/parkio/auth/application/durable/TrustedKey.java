package com.parkio.auth.application.durable;

import java.time.Instant;
import java.util.Objects;

/**
 * One producer key of evidence format v2: the HMAC secret with its {@code keyId}, the producer
 * it belongs to, its signing window and its retirement state. The producer signs only inside
 * {@code [notBefore, notAfter)} with a key that is not retired; a verifier refuses a retired
 * key and a key used before its {@code notBefore}, but not objects signed before the window
 * closed (they are write-once and cannot be re-signed). The secret never appears in
 * {@link #toString()}.
 */
public record TrustedKey(String keyId, String producerId, byte[] key, Instant notBefore, Instant notAfter,
                         boolean retired) {

    public TrustedKey {
        requireText(keyId, "keyId");
        requireText(producerId, "producerId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(notBefore, "notBefore");
        if (key.length == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (notAfter != null && !notAfter.isAfter(notBefore)) {
            throw new DurableEvidenceException("key " + keyId + " notAfter must be after notBefore");
        }
        key = key.clone();
    }

    /** An active key without an end of its signing window. */
    public static TrustedKey active(String keyId, String producerId, byte[] key, Instant notBefore) {
        return new TrustedKey(keyId, producerId, key, notBefore, null, false);
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    /** Producer rule: not retired and {@code notBefore <= at < notAfter}. */
    public boolean signsAt(Instant at) {
        return !retired && !at.isBefore(notBefore) && (notAfter == null || at.isBefore(notAfter));
    }

    @Override
    public String toString() {
        return "TrustedKey[keyId=" + keyId + ", producerId=" + producerId + ", key=<redacted>, notBefore="
                + notBefore + ", notAfter=" + notAfter + ", retired=" + retired + "]";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
