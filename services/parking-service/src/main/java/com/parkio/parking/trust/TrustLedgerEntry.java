package com.parkio.parking.trust;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable append-only trust ledger entry. */
public record TrustLedgerEntry(
        UUID ledgerEntryId,
        UUID evaluationId,
        TrustSubject subject,
        TrustDomain domain,
        String trustPolicyVersion,
        TrustSnapshotSchemaVersion snapshotSchemaVersion,
        String attributionMappingVersion,
        UUID sourceOutcomeRecordId,
        UUID sourceEvidenceId,
        UUID sourceEvidenceGroupId,
        TrustEvidence.Type evidenceType,
        TrustEvidence.ContributionRole contributionRole,
        TrustEvidence.AttributionQuality attributionQuality,
        TrustEvidence.Eligibility eligibility,
        TrustEvaluation.Direction direction,
        TrustSnapshot.Level trustLevel,
        Instant evaluatedAt,
        Instant createdAt,
        TrustEvidence evidence,
        TrustSnapshot previousSnapshot,
        TrustEvaluation evaluation) {

    public TrustLedgerEntry {
        Objects.requireNonNull(ledgerEntryId, "ledgerEntryId");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(trustPolicyVersion, "trustPolicyVersion");
        Objects.requireNonNull(snapshotSchemaVersion, "snapshotSchemaVersion");
        Objects.requireNonNull(attributionMappingVersion, "attributionMappingVersion");
        Objects.requireNonNull(sourceOutcomeRecordId, "sourceOutcomeRecordId");
        Objects.requireNonNull(sourceEvidenceId, "sourceEvidenceId");
        Objects.requireNonNull(sourceEvidenceGroupId, "sourceEvidenceGroupId");
        Objects.requireNonNull(evidenceType, "evidenceType");
        Objects.requireNonNull(contributionRole, "contributionRole");
        Objects.requireNonNull(attributionQuality, "attributionQuality");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(trustLevel, "trustLevel");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(previousSnapshot, "previousSnapshot");
        Objects.requireNonNull(evaluation, "evaluation");
    }
}

