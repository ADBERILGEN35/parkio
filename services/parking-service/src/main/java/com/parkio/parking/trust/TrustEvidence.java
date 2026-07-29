package com.parkio.parking.trust;

import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeReason;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Canonical trust-learning evidence derived from immutable validated outcomes. */
public record TrustEvidence(
        UUID evidenceId,
        UUID evidenceGroupId,
        TrustSubject subject,
        TrustDomain domain,
        Type evidenceType,
        ContributionRole contributionRole,
        AttributionQuality attributionQuality,
        Eligibility eligibility,
        OutcomeClassification outcomeClassification,
        int outcomeConfidence,
        OutcomeReason primaryOutcomeReason,
        Set<OutcomeReason> outcomeReasons,
        UUID sourceOutcomeRecordId,
        UUID sourceOutcomeEvaluationId,
        UUID parkingSpotId,
        Instant occurredAt,
        Instant validatedAt,
        String outcomePolicyVersion,
        String attributionMappingVersion) {

    public TrustEvidence {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(evidenceGroupId, "evidenceGroupId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(evidenceType, "evidenceType");
        Objects.requireNonNull(contributionRole, "contributionRole");
        Objects.requireNonNull(attributionQuality, "attributionQuality");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(outcomeClassification, "outcomeClassification");
        if (outcomeConfidence < 0 || outcomeConfidence > 100) {
            throw new IllegalArgumentException("outcomeConfidence must be between 0 and 100");
        }
        Objects.requireNonNull(primaryOutcomeReason, "primaryOutcomeReason");
        outcomeReasons = outcomeReasons == null || outcomeReasons.isEmpty()
                ? Set.of(primaryOutcomeReason)
                : Set.copyOf(outcomeReasons);
        Objects.requireNonNull(sourceOutcomeRecordId, "sourceOutcomeRecordId");
        Objects.requireNonNull(sourceOutcomeEvaluationId, "sourceOutcomeEvaluationId");
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(validatedAt, "validatedAt");
        Objects.requireNonNull(outcomePolicyVersion, "outcomePolicyVersion");
        Objects.requireNonNull(attributionMappingVersion, "attributionMappingVersion");
    }

    public enum Type {
        VALIDATED_OUTCOME_REPORTER
    }

    public enum ContributionRole {
        REPORTER
    }

    public enum AttributionQuality {
        DIRECT,
        STRONG,
        PARTIAL,
        AMBIGUOUS,
        NONE
    }

    public enum Eligibility {
        ELIGIBLE,
        UNSUPPORTED_SUBJECT,
        AMBIGUOUS_OUTCOME,
        OUTCOME_NEUTRAL,
        SELF_CONFIRMED,
        BELOW_CONFIDENCE_THRESHOLD,
        INSUFFICIENT_ATTRIBUTION
    }
}

