package com.parkio.parking.trust;

import java.time.Instant;
import java.util.Objects;

/** Deterministic result of applying one trust evidence item. */
public record TrustEvaluation(
        TrustEvidence evidence,
        TrustSnapshot previousSnapshot,
        TrustSnapshot resultingSnapshot,
        int positiveEvidenceDelta,
        int negativeEvidenceDelta,
        Direction direction,
        String decisiveReason,
        String trustPolicyVersion,
        Instant evaluatedAt) {

    public TrustEvaluation {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(previousSnapshot, "previousSnapshot");
        Objects.requireNonNull(resultingSnapshot, "resultingSnapshot");
        if (positiveEvidenceDelta < 0 || negativeEvidenceDelta < 0) {
            throw new IllegalArgumentException("evidence deltas cannot be negative");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(decisiveReason, "decisiveReason");
        Objects.requireNonNull(trustPolicyVersion, "trustPolicyVersion");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    public enum Direction {
        POSITIVE,
        NEUTRAL,
        NEGATIVE
    }
}

