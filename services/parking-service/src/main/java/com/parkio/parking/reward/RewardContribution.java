package com.parkio.parking.reward;

import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeReason;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Canonical immutable reward input derived from validated outcome history. */
public record RewardContribution(
        UUID contributionId,
        UUID evidenceGroupId,
        RewardSubject subject,
        ContributionRole contributionRole,
        AttributionQuality attributionQuality,
        Eligibility eligibility,
        EligibilityReason primaryEligibilityReason,
        Set<EligibilityReason> eligibilityReasons,
        UUID sourceOutcomeRecordId,
        UUID sourceEvaluationId,
        UUID sourceParkingSpotId,
        OutcomeClassification outcomeClassification,
        int outcomeConfidence,
        String outcomeConfidenceBand,
        OutcomeReason primaryOutcomeReason,
        Set<OutcomeReason> outcomeReasons,
        Instant publishedAt,
        Instant evaluatedAt,
        String attributionMappingVersion) {

    public RewardContribution {
        Objects.requireNonNull(contributionId, "contributionId");
        Objects.requireNonNull(evidenceGroupId, "evidenceGroupId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(contributionRole, "contributionRole");
        Objects.requireNonNull(attributionQuality, "attributionQuality");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(primaryEligibilityReason, "primaryEligibilityReason");
        eligibilityReasons = eligibilityReasons == null || eligibilityReasons.isEmpty()
                ? Set.of(primaryEligibilityReason)
                : Set.copyOf(eligibilityReasons);
        Objects.requireNonNull(sourceOutcomeRecordId, "sourceOutcomeRecordId");
        Objects.requireNonNull(sourceEvaluationId, "sourceEvaluationId");
        Objects.requireNonNull(sourceParkingSpotId, "sourceParkingSpotId");
        Objects.requireNonNull(outcomeClassification, "outcomeClassification");
        if (outcomeConfidence < 0 || outcomeConfidence > 100) {
            throw new IllegalArgumentException("outcomeConfidence must be between 0 and 100");
        }
        Objects.requireNonNull(outcomeConfidenceBand, "outcomeConfidenceBand");
        Objects.requireNonNull(primaryOutcomeReason, "primaryOutcomeReason");
        outcomeReasons = outcomeReasons == null || outcomeReasons.isEmpty()
                ? Set.of(primaryOutcomeReason)
                : Set.copyOf(outcomeReasons);
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(attributionMappingVersion, "attributionMappingVersion");
    }

    public enum ContributionRole {
        REPORTER,
        VERIFIER,
        CLAIMANT,
        FILLED_REPORTER,
        MODERATOR,
        SYSTEM
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
        DEFERRED_FINALITY,
        OUTCOME_NOT_REWARDABLE,
        AMBIGUOUS_ATTRIBUTION,
        UNSUPPORTED_ROLE,
        SELF_CONFIRMATION_BLOCKED
    }

    public enum EligibilityReason {
        FINAL_CONFIRMED_CORRECT,
        FINAL_CONFIRMED_INCORRECT,
        FINAL_EXPIRED_WITHOUT_EVIDENCE,
        DIRECT_COMMUNITY_CLAIM,
        DIRECT_MULTI_VERIFICATION,
        STRONG_SINGLE_VERIFICATION,
        OUTCOME_NOT_FINAL,
        OUTCOME_AMBIGUOUS,
        UNSUPPORTED_ROLE,
        SELF_CONFIRMATION_BLOCKED,
        MODERATOR_OR_SYSTEM_ACTION
    }
}
