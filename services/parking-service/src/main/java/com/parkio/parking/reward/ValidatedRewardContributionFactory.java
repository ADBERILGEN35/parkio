package com.parkio.parking.reward;

import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Repository-backed mapping from immutable validated outcomes to canonical reward inputs. */
public final class ValidatedRewardContributionFactory {

    public static final String ATTRIBUTION_MAPPING_VERSION = "reward-attribution-v1";

    private ValidatedRewardContributionFactory() {}

    public static RewardContribution reporterContribution(OutcomeHistoryRecord outcome, UUID reporterUserId) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reporterUserId, "reporterUserId");
        RewardSubject subject = new RewardSubject(RewardSubject.Type.USER, reporterUserId);
        RewardContribution.AttributionQuality attributionQuality =
                attributionQuality(outcome.classification(), outcome.primaryReason());
        RewardContribution.Eligibility eligibility =
                eligibility(outcome.classification(), outcome.primaryReason(), attributionQuality);
        RewardContribution.EligibilityReason primaryReason =
                primaryEligibilityReason(outcome.classification(), outcome.primaryReason(), attributionQuality);
        UUID contributionId = deterministicId("reward-contribution|reporter|" + outcome.parkingSpotId() + "|" + reporterUserId);
        return new RewardContribution(
                contributionId,
                contributionId,
                subject,
                RewardContribution.ContributionRole.REPORTER,
                attributionQuality,
                eligibility,
                primaryReason,
                Set.of(primaryReason),
                outcome.recordId(),
                outcome.evaluationId(),
                outcome.parkingSpotId(),
                outcome.classification(),
                outcome.confidence().value(),
                confidenceBand(outcome.confidence().value()),
                outcome.primaryReason(),
                Set.copyOf(outcome.snapshot().evaluation().reasons()),
                outcome.snapshot().evaluation().timeline().publishedAt(),
                outcome.evaluatedAt(),
                ATTRIBUTION_MAPPING_VERSION);
    }

    private static RewardContribution.AttributionQuality attributionQuality(
            OutcomeClassification classification,
            OutcomeReason primaryReason) {
        return switch (primaryReason) {
            case COMMUNITY_CLAIM_CONFIRMED, MULTIPLE_AVAILABLE_VERIFICATIONS -> RewardContribution.AttributionQuality.DIRECT;
            case SINGLE_AVAILABLE_VERIFICATION -> RewardContribution.AttributionQuality.STRONG;
            case NEGATIVE_VERIFICATION, MODERATOR_REJECTION -> RewardContribution.AttributionQuality.DIRECT;
            case TIME_EXPIRED, TIME_EXPIRED_NO_EVIDENCE, COMMUNITY_FILLED_REPORTS,
                    VALIDATION_WINDOW_OPEN, INSUFFICIENT_EVIDENCE, TERMINAL_STATUS -> RewardContribution.AttributionQuality.AMBIGUOUS;
            case AI_REJECTION, REVIEW_FAILED -> classification == OutcomeClassification.CONFIRMED_INCORRECT
                    ? RewardContribution.AttributionQuality.PARTIAL
                    : RewardContribution.AttributionQuality.NONE;
            case NOT_YET_PUBLISHED -> RewardContribution.AttributionQuality.NONE;
        };
    }

    private static RewardContribution.Eligibility eligibility(
            OutcomeClassification classification,
            OutcomeReason primaryReason,
            RewardContribution.AttributionQuality attributionQuality) {
        if (classification == OutcomeClassification.LIKELY_CORRECT
                || classification == OutcomeClassification.LIKELY_INCORRECT
                || classification == OutcomeClassification.UNKNOWN) {
            return RewardContribution.Eligibility.DEFERRED_FINALITY;
        }
        if (attributionQuality == RewardContribution.AttributionQuality.NONE) {
            return RewardContribution.Eligibility.OUTCOME_NOT_REWARDABLE;
        }
        if (attributionQuality == RewardContribution.AttributionQuality.AMBIGUOUS) {
            return RewardContribution.Eligibility.AMBIGUOUS_ATTRIBUTION;
        }
        if (classification == OutcomeClassification.CONFIRMED_CORRECT) {
            return RewardContribution.Eligibility.ELIGIBLE;
        }
        return switch (primaryReason) {
            case MODERATOR_REJECTION, NEGATIVE_VERIFICATION, AI_REJECTION, REVIEW_FAILED,
                    TIME_EXPIRED, TIME_EXPIRED_NO_EVIDENCE, COMMUNITY_FILLED_REPORTS -> RewardContribution.Eligibility.OUTCOME_NOT_REWARDABLE;
            default -> RewardContribution.Eligibility.AMBIGUOUS_ATTRIBUTION;
        };
    }

    private static RewardContribution.EligibilityReason primaryEligibilityReason(
            OutcomeClassification classification,
            OutcomeReason primaryReason,
            RewardContribution.AttributionQuality attributionQuality) {
        if (classification == OutcomeClassification.LIKELY_CORRECT
                || classification == OutcomeClassification.LIKELY_INCORRECT
                || classification == OutcomeClassification.UNKNOWN) {
            return RewardContribution.EligibilityReason.OUTCOME_NOT_FINAL;
        }
        if (classification == OutcomeClassification.EXPIRED_WITHOUT_EVIDENCE) {
            return RewardContribution.EligibilityReason.FINAL_EXPIRED_WITHOUT_EVIDENCE;
        }
        if (classification == OutcomeClassification.CONFIRMED_INCORRECT) {
            return RewardContribution.EligibilityReason.FINAL_CONFIRMED_INCORRECT;
        }
        if (attributionQuality == RewardContribution.AttributionQuality.DIRECT) {
            return switch (primaryReason) {
                case COMMUNITY_CLAIM_CONFIRMED -> RewardContribution.EligibilityReason.DIRECT_COMMUNITY_CLAIM;
                case MULTIPLE_AVAILABLE_VERIFICATIONS -> RewardContribution.EligibilityReason.DIRECT_MULTI_VERIFICATION;
                default -> RewardContribution.EligibilityReason.FINAL_CONFIRMED_CORRECT;
            };
        }
        if (attributionQuality == RewardContribution.AttributionQuality.STRONG) {
            return RewardContribution.EligibilityReason.STRONG_SINGLE_VERIFICATION;
        }
        return RewardContribution.EligibilityReason.OUTCOME_AMBIGUOUS;
    }

    private static String confidenceBand(int confidence) {
        if (confidence >= 85) {
            return "HIGH";
        }
        if (confidence >= 70) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static UUID deterministicId(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
