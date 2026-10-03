package com.parkio.auth.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * What the durable store holds for a request: the canonical stored version, the SHA-256 of its
 * bytes, and the retention lock on that version. A caller can later prove, from the store alone,
 * which version it published and that it is still locked.
 *
 * @param versionId the store's version id of the canonical (first) version
 * @param sha256 hex SHA-256 of that version's bytes
 * @param retentionMode the lock mode on that version, for example {@code COMPLIANCE}
 * @param retainUntil until when that version is locked
 */
public record DurableErasureReceipt(String versionId, String sha256, String retentionMode, Instant retainUntil) {

    public DurableErasureReceipt {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(retentionMode, "retentionMode");
        Objects.requireNonNull(retainUntil, "retainUntil");
    }
}
