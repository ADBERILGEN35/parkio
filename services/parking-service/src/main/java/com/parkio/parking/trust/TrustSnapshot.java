package com.parkio.parking.trust;

import java.time.Instant;
import java.util.Objects;

/** Derived current state for one subject/domain. */
public record TrustSnapshot(
        TrustSubject subject,
        TrustDomain domain,
        String trustPolicyVersion,
        TrustSnapshotSchemaVersion snapshotSchemaVersion,
        TrustScore score,
        TrustConfidence confidence,
        int positiveEvidenceMass,
        int negativeEvidenceMass,
        int effectiveEvidenceCount,
        Level level,
        Instant lastEvaluatedAt) {

    public TrustSnapshot {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(trustPolicyVersion, "trustPolicyVersion");
        Objects.requireNonNull(snapshotSchemaVersion, "snapshotSchemaVersion");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(confidence, "confidence");
        if (positiveEvidenceMass < 0 || negativeEvidenceMass < 0) {
            throw new IllegalArgumentException("evidence mass cannot be negative");
        }
        if (effectiveEvidenceCount < 0) {
            throw new IllegalArgumentException("effectiveEvidenceCount cannot be negative");
        }
        Objects.requireNonNull(level, "level");
    }

    public static TrustSnapshot initial(
            TrustSubject subject,
            TrustDomain domain,
            String trustPolicyVersion,
            TrustSnapshotSchemaVersion snapshotSchemaVersion,
            TrustScore score,
            TrustConfidence confidence,
            Level level,
            Instant evaluatedAt) {
        return new TrustSnapshot(
                subject,
                domain,
                trustPolicyVersion,
                snapshotSchemaVersion,
                score,
                confidence,
                0,
                0,
                0,
                level,
                evaluatedAt);
    }

    public enum Level {
        UNKNOWN,
        LOW_CONFIDENCE,
        DEVELOPING,
        ESTABLISHED,
        HIGH_CONFIDENCE
    }
}

