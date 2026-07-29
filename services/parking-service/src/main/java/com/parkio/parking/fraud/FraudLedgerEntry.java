package com.parkio.parking.fraud;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable append-only fraud evaluation ledger entry. */
public record FraudLedgerEntry(
        UUID ledgerEntryId,
        UUID evaluationId,
        FraudSubject subject,
        FraudDomain domain,
        String policyVersion,
        FraudSnapshotSchemaVersion snapshotSchemaVersion,
        String mappingVersion,
        String aggregationVersion,
        UUID sourceOutcomeRecordId,
        Instant evidenceWindowStart,
        Instant evidenceWindowEnd,
        int riskScoreBasisPoints,
        FraudRiskBand riskBand,
        FraudConfidenceBand confidenceBand,
        int effectiveEvidenceCount,
        FraudDisposition disposition,
        String decisiveRule,
        Instant evaluatedAt,
        Instant createdAt,
        FraudSnapshot snapshot) {

    public FraudLedgerEntry {
        Objects.requireNonNull(ledgerEntryId, "ledgerEntryId");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(snapshotSchemaVersion, "snapshotSchemaVersion");
        Objects.requireNonNull(mappingVersion, "mappingVersion");
        Objects.requireNonNull(aggregationVersion, "aggregationVersion");
        Objects.requireNonNull(sourceOutcomeRecordId, "sourceOutcomeRecordId");
        Objects.requireNonNull(evidenceWindowStart, "evidenceWindowStart");
        Objects.requireNonNull(evidenceWindowEnd, "evidenceWindowEnd");
        Objects.requireNonNull(riskBand, "riskBand");
        Objects.requireNonNull(confidenceBand, "confidenceBand");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(decisiveRule, "decisiveRule");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(snapshot, "snapshot");
        if (riskScoreBasisPoints < 0 || riskScoreBasisPoints > FraudRiskScore.MAX_BASIS_POINTS) {
            throw new IllegalArgumentException("risk score out of bounds");
        }
        if (effectiveEvidenceCount < 0) {
            throw new IllegalArgumentException("effectiveEvidenceCount must be non-negative");
        }
    }
}
