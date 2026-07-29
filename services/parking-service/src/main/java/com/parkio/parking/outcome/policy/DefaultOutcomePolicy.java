package com.parkio.parking.outcome.policy;

import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.confidence.OutcomeConfidence;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.signal.OutcomeSignalType;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Reference v1 outcome validation policy.
 *
 * <p>Read-only: never mutates publication, availability, trust, or rewards.
 */
public final class DefaultOutcomePolicy implements OutcomePolicy {

    private final OutcomePolicyConfig config;

    public DefaultOutcomePolicy() {
        this(OutcomePolicyConfig.referenceV1());
    }

    public DefaultOutcomePolicy(OutcomePolicyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public OutcomeEvaluation evaluate(OutcomeEvidence evidence, OutcomeEvaluationContext context) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");

        OutcomeTimeline timeline = evidence.timeline();
        Duration validationAge = timeline.validationAgeAt(context.evaluatedAt());
        boolean windowOpen = evidence.isValidationWindowOpen(context.evaluatedAt());
        Set<OutcomeReason> reasons = new LinkedHashSet<>();

        if (!evidence.isPublished()) {
            reasons.add(OutcomeReason.NOT_YET_PUBLISHED);
            return build(evidence, context, OutcomeClassification.UNKNOWN, OutcomeReason.NOT_YET_PUBLISHED,
                    reasons, validationAge, windowOpen);
        }

        if (windowOpen && evidence.status().isTerminal() == false
                && evidence.status() != ParkingSpotStatus.SUSPICIOUS) {
            long postPublishSignals = timeline.signals().stream()
                    .filter(signal -> signal.type() != OutcomeSignalType.PUBLISHED
                            && signal.type() != OutcomeSignalType.AI_PUBLISHED
                            && signal.type() != OutcomeSignalType.MODERATOR_APPROVED)
                    .count();
            if (postPublishSignals == 0 && evidence.verificationCount() == 0 && evidence.filledReportCount() == 0) {
                reasons.add(OutcomeReason.VALIDATION_WINDOW_OPEN);
                return build(evidence, context, OutcomeClassification.UNKNOWN, OutcomeReason.VALIDATION_WINDOW_OPEN,
                        reasons, validationAge, windowOpen);
            }
        }

        if (isConfirmedCorrect(evidence, timeline, reasons)) {
            return build(evidence, context, OutcomeClassification.CONFIRMED_CORRECT,
                    primaryConfirmedCorrect(evidence, timeline), reasons, validationAge, windowOpen);
        }

        if (isConfirmedIncorrect(evidence, timeline, reasons)) {
            return build(evidence, context, OutcomeClassification.CONFIRMED_INCORRECT,
                    primaryConfirmedIncorrect(evidence, timeline), reasons, validationAge, windowOpen);
        }

        if (isExpiredWithoutEvidence(evidence, timeline, reasons)) {
            reasons.add(OutcomeReason.TIME_EXPIRED_NO_EVIDENCE);
            return build(evidence, context, OutcomeClassification.EXPIRED_WITHOUT_EVIDENCE,
                    OutcomeReason.TIME_EXPIRED_NO_EVIDENCE, reasons, validationAge, windowOpen);
        }

        if (isLikelyIncorrect(evidence, timeline, reasons)) {
            return build(evidence, context, OutcomeClassification.LIKELY_INCORRECT,
                    primaryLikelyIncorrect(evidence, timeline), reasons, validationAge, windowOpen);
        }

        if (isLikelyCorrect(evidence, timeline, reasons)) {
            return build(evidence, context, OutcomeClassification.LIKELY_CORRECT,
                    primaryLikelyCorrect(evidence, timeline), reasons, validationAge, windowOpen);
        }

        reasons.add(OutcomeReason.INSUFFICIENT_EVIDENCE);
        return build(evidence, context, OutcomeClassification.UNKNOWN, OutcomeReason.INSUFFICIENT_EVIDENCE,
                reasons, validationAge, windowOpen);
    }

    private static boolean isConfirmedCorrect(
            OutcomeEvidence evidence, OutcomeTimeline timeline, Set<OutcomeReason> reasons) {
        if (timeline.hasSignalType(OutcomeSignalType.COMMUNITY_CLAIM)) {
            reasons.add(OutcomeReason.COMMUNITY_CLAIM_CONFIRMED);
            return true;
        }
        long available = timeline.countSignalType(OutcomeSignalType.VERIFICATION_AVAILABLE);
        if (available >= OutcomePolicyConfig.MULTIPLE_AVAILABLE_VERIFICATIONS_MIN
                || evidence.verificationCount() >= OutcomePolicyConfig.MULTIPLE_AVAILABLE_VERIFICATIONS_MIN) {
            reasons.add(OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS);
            return true;
        }
        if (evidence.status() == ParkingSpotStatus.VERIFIED && evidence.filledReportCount() == 0
                && evidence.verificationCount() >= 1) {
            reasons.add(OutcomeReason.SINGLE_AVAILABLE_VERIFICATION);
            return true;
        }
        return false;
    }

    private static boolean isConfirmedIncorrect(
            OutcomeEvidence evidence, OutcomeTimeline timeline, Set<OutcomeReason> reasons) {
        if (evidence.status() == ParkingSpotStatus.REJECTED) {
            reasons.add(timeline.hasSignalType(OutcomeSignalType.MODERATOR_REJECTED)
                    ? OutcomeReason.MODERATOR_REJECTION
                    : OutcomeReason.AI_REJECTION);
            return true;
        }
        if (evidence.status() == ParkingSpotStatus.REVIEW_FAILED) {
            reasons.add(OutcomeReason.REVIEW_FAILED);
            return true;
        }
        if (evidence.filledReportCount() >= OutcomePolicyConfig.FILLED_REPORTS_TERMINAL_MIN
                || timeline.countSignalType(OutcomeSignalType.VERIFICATION_FILLED)
                        >= OutcomePolicyConfig.FILLED_REPORTS_TERMINAL_MIN) {
            reasons.add(OutcomeReason.COMMUNITY_FILLED_REPORTS);
            return true;
        }
        if (timeline.hasSignalType(OutcomeSignalType.VERIFICATION_ILLEGAL_OR_RISKY)
                || timeline.hasSignalType(OutcomeSignalType.AI_REJECTED)) {
            reasons.add(OutcomeReason.NEGATIVE_VERIFICATION);
            return true;
        }
        return false;
    }

    private static boolean isExpiredWithoutEvidence(
            OutcomeEvidence evidence, OutcomeTimeline timeline, Set<OutcomeReason> reasons) {
        if (evidence.status() != ParkingSpotStatus.EXPIRED) {
            return false;
        }
        reasons.add(OutcomeReason.TIME_EXPIRED);
        return evidence.verificationCount() == 0
                && evidence.filledReportCount() == 0
                && !timeline.hasSignalType(OutcomeSignalType.COMMUNITY_CLAIM)
                && timeline.countSignalType(OutcomeSignalType.VERIFICATION_AVAILABLE) == 0;
    }

    private static boolean isLikelyIncorrect(
            OutcomeEvidence evidence, OutcomeTimeline timeline, Set<OutcomeReason> reasons) {
        if (evidence.status() == ParkingSpotStatus.SUSPICIOUS) {
            reasons.add(OutcomeReason.COMMUNITY_FILLED_REPORTS);
            return true;
        }
        if (evidence.filledReportCount() == 1 || timeline.hasSignalType(OutcomeSignalType.VERIFICATION_FILLED)) {
            reasons.add(OutcomeReason.COMMUNITY_FILLED_REPORTS);
            return true;
        }
        if (timeline.hasSignalType(OutcomeSignalType.VERIFICATION_INVALID)
                || timeline.hasSignalType(OutcomeSignalType.VERIFICATION_WRONG_VEHICLE_SIZE)) {
            reasons.add(OutcomeReason.NEGATIVE_VERIFICATION);
            return true;
        }
        return false;
    }

    private static boolean isLikelyCorrect(
            OutcomeEvidence evidence, OutcomeTimeline timeline, Set<OutcomeReason> reasons) {
        if (evidence.status() == ParkingSpotStatus.EXPIRED
                && (evidence.verificationCount() > 0
                        || timeline.hasSignalType(OutcomeSignalType.VERIFICATION_AVAILABLE))) {
            reasons.add(OutcomeReason.SINGLE_AVAILABLE_VERIFICATION);
            return true;
        }
        if (evidence.status() == ParkingSpotStatus.FILLED
                && evidence.filledReportCount() < OutcomePolicyConfig.FILLED_REPORTS_TERMINAL_MIN
                && !timeline.hasSignalType(OutcomeSignalType.VERIFICATION_FILLED)) {
            reasons.add(OutcomeReason.COMMUNITY_CLAIM_CONFIRMED);
            return true;
        }
        return false;
    }

    private static OutcomeReason primaryConfirmedCorrect(OutcomeEvidence evidence, OutcomeTimeline timeline) {
        if (timeline.hasSignalType(OutcomeSignalType.COMMUNITY_CLAIM)) {
            return OutcomeReason.COMMUNITY_CLAIM_CONFIRMED;
        }
        if (evidence.verificationCount() >= OutcomePolicyConfig.MULTIPLE_AVAILABLE_VERIFICATIONS_MIN) {
            return OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS;
        }
        return OutcomeReason.SINGLE_AVAILABLE_VERIFICATION;
    }

    private static OutcomeReason primaryConfirmedIncorrect(OutcomeEvidence evidence, OutcomeTimeline timeline) {
        if (evidence.status() == ParkingSpotStatus.REJECTED) {
            return timeline.hasSignalType(OutcomeSignalType.MODERATOR_REJECTED)
                    ? OutcomeReason.MODERATOR_REJECTION
                    : OutcomeReason.AI_REJECTION;
        }
        if (evidence.status() == ParkingSpotStatus.REVIEW_FAILED) {
            return OutcomeReason.REVIEW_FAILED;
        }
        return OutcomeReason.COMMUNITY_FILLED_REPORTS;
    }

    private static OutcomeReason primaryLikelyIncorrect(OutcomeEvidence evidence, OutcomeTimeline timeline) {
        return timeline.hasSignalType(OutcomeSignalType.VERIFICATION_INVALID)
                        || timeline.hasSignalType(OutcomeSignalType.VERIFICATION_WRONG_VEHICLE_SIZE)
                ? OutcomeReason.NEGATIVE_VERIFICATION
                : OutcomeReason.COMMUNITY_FILLED_REPORTS;
    }

    private static OutcomeReason primaryLikelyCorrect(OutcomeEvidence evidence, OutcomeTimeline timeline) {
        return evidence.status() == ParkingSpotStatus.FILLED
                ? OutcomeReason.COMMUNITY_CLAIM_CONFIRMED
                : OutcomeReason.SINGLE_AVAILABLE_VERIFICATION;
    }

    private OutcomeEvaluation build(
            OutcomeEvidence evidence,
            OutcomeEvaluationContext context,
            OutcomeClassification classification,
            OutcomeReason primaryReason,
            Set<OutcomeReason> reasons,
            Duration validationAge,
            boolean windowOpen) {
        return new OutcomeEvaluation(
                evidence.parkingSpotId(),
                classification,
                OutcomeConfidence.of(config.confidenceFor(classification)),
                primaryReason,
                reasons,
                evidence.timeline(),
                validationAge,
                windowOpen,
                context.policyVersion(),
                context.evaluatedAt());
    }
}