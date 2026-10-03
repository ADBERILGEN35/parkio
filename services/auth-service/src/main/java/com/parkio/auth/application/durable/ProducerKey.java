package com.parkio.auth.application.durable;

import java.util.Objects;

/**
 * A producer's identity and HMAC key. Consumers look keys up by {@code producerId}, which is
 * the key identifier of format v1 (there is no separate key id; rotation is a separate task).
 * The key never appears in {@link #toString()}.
 */
public record ProducerKey(String producerId, byte[] key) {

    public ProducerKey {
        Objects.requireNonNull(producerId, "producerId");
        Objects.requireNonNull(key, "key");
        if (producerId.isBlank()) {
            throw new IllegalArgumentException("producerId must not be blank");
        }
        if (key.length == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        key = key.clone();
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    @Override
    public String toString() {
        return "ProducerKey[producerId=" + producerId + ", key=<redacted>]";
    }
}
