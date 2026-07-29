package com.parkio.parking.trust;

import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Repository-backed mapping from immutable validated outcomes to canonical trust evidence. */
public final class ValidatedTrustEvidenceFactory {

    public static final String ATTRIBUTION_MAPPING_VERSION = "trust-attribution-v1";

    private ValidatedTrustEvidenceFactory() {}

    public static TrustEvidence reporterEvidence(OutcomeHistoryRecord outcome, UUID reporterUserId) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reporterUserId, "reporterUserId");
        TrustSubject subject = new TrustSubject(TrustSubjectType.REPORTER, reporterUserId);
        TrustEvidence.AttributionQuality attributionQuality = attributionQuality(outcome.classification(), outcome.primaryReason());
        TrustEvidence.Eligibility eligibility = eligibility(outcome.classification(), outcome.primaryReason(), attributionQuality);
        UUID evidenceGroupId = outcome.recordId();
        return new TrustEvidence(
                deterministicId(
                        "trust-evidence|reporter|" + outcome.recordId() + "|" + reporterUserId + "|"
                                + TrustDomain.PARKING_REPORT_ACCURACY + "|"
                                + TrustPolicyConfig.POLICY_VERSION),
                evidenceGroupId,
                subject,
                TrustDomain.PARKING_REPORT_ACCURACY,
                TrustEvidence.Type.VALIDATED_OUTCOME_REPORTER,
                TrustEvidence.ContributionRole.REPORTER,
                attributionQuality,
                eligibility,
                outcome.classification(),
                outcome.confidence().value(),
                outcome.primaryReason(),
                Set.copyOf(outcome.snapshot().evaluation().reasons()),
                outcome.recordId(),
                outcome.evaluationId(),
                outcome.parkingSpotId(),
                outcome.snapshot().evaluation().timeline().publishedAt() == null
                        ? outcome.evaluatedAt()
                        : outcome.snapshot().evaluation().timeline().publishedAt(),
                outcome.evaluatedAt(),
                outcome.policyVersion().value(),
                ATTRIBUTION_MAPPING_VERSION);
    }

    private static TrustEvidence.AttributionQuality attributionQuality(
            OutcomeClassification classification,
            OutcomeReason primaryReason) {
        return switch (primaryReason) {
            case COMMUNITY_CLAIM_CONFIRMED, MULTIPLE_AVAILABLE_VERIFICATIONS -> TrustEvidence.AttributionQuality.DIRECT;
            case SINGLE_AVAILABLE_VERIFICATION -> TrustEvidence.AttributionQuality.STRONG;
            case NEGATIVE_VERIFICATION, MODERATOR_REJECTION -> TrustEvidence.AttributionQuality.DIRECT;
            case COMMUNITY_FILLED_REPORTS, TIME_EXPIRED, TIME_EXPIRED_NO_EVIDENCE,
                    VALIDATION_WINDOW_OPEN, INSUFFICIENT_EVIDENCE, TERMINAL_STATUS -> TrustEvidence.AttributionQuality.AMBIGUOUS;
            case AI_REJECTION, REVIEW_FAILED -> classification == OutcomeClassification.CONFIRMED_INCORRECT
                    ? TrustEvidence.AttributionQuality.PARTIAL
                    : TrustEvidence.AttributionQuality.NONE;
            case NOT_YET_PUBLISHED -> TrustEvidence.AttributionQuality.NONE;
        };
    }

    private static TrustEvidence.Eligibility eligibility(
            OutcomeClassification classification,
            OutcomeReason primaryReason,
            TrustEvidence.AttributionQuality attributionQuality) {
        if (attributionQuality == TrustEvidence.AttributionQuality.NONE) {
            return TrustEvidence.Eligibility.INSUFFICIENT_ATTRIBUTION;
        }
        if (attributionQuality == TrustEvidence.AttributionQuality.AMBIGUOUS) {
            return TrustEvidence.Eligibility.AMBIGUOUS_OUTCOME;
        }
        return switch (classification) {
            case CONFIRMED_CORRECT, LIKELY_CORRECT -> TrustEvidence.Eligibility.ELIGIBLE;
            case UNKNOWN, EXPIRED_WITHOUT_EVIDENCE -> TrustEvidence.Eligibility.OUTCOME_NEUTRAL;
            case LIKELY_INCORRECT -> primaryReason == OutcomeReason.NEGATIVE_VERIFICATION
                    ? TrustEvidence.Eligibility.ELIGIBLE
                    : TrustEvidence.Eligibility.AMBIGUOUS_OUTCOME;
            case CONFIRMED_INCORRECT -> switch (primaryReason) {
                case NEGATIVE_VERIFICATION, MODERATOR_REJECTION -> TrustEvidence.Eligibility.ELIGIBLE;
                case COMMUNITY_FILLED_REPORTS, TIME_EXPIRED, TIME_EXPIRED_NO_EVIDENCE -> TrustEvidence.Eligibility.AMBIGUOUS_OUTCOME;
                default -> TrustEvidence.Eligibility.INSUFFICIENT_ATTRIBUTION;
            };
        };
    }

    private static UUID deterministicId(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}

