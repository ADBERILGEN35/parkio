package com.parkio.parking.trust;

import java.time.Instant;
import java.util.Objects;

/** Deterministic evaluation context for trust updates and replay. */
public record TrustEvaluationContext(
        Instant evaluatedAt,
        String trustPolicyVersion,
        TrustSnapshotSchemaVersion snapshotSchemaVersion) {

    public TrustEvaluationContext {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(trustPolicyVersion, "trustPolicyVersion");
        Objects.requireNonNull(snapshotSchemaVersion, "snapshotSchemaVersion");
    }
}

