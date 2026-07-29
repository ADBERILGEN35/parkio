package com.parkio.parking.fraud;

import java.time.Instant;
import java.util.Objects;

/** Immutable replay snapshot for fraud evaluation. */
public record FraudSnapshot(
        FraudSubject subject,
        FraudDomain domain,
        String policyVersion,
        FraudSnapshotSchemaVersion snapshotSchemaVersion,
        String mappingVersion,
        String aggregationVersion,
        FraudEvaluationContext context,
        FraudFeatureVector featureVector,
        FraudEvaluation evaluation,
        Instant evaluatedAt) {

    public FraudSnapshot {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(snapshotSchemaVersion, "snapshotSchemaVersion");
        Objects.requireNonNull(mappingVersion, "mappingVersion");
        Objects.requireNonNull(aggregationVersion, "aggregationVersion");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(featureVector, "featureVector");
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }
}
