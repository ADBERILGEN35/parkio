package com.parkio.parking.availability.policy;

import com.parkio.parking.availability.AvailabilityEvaluation;
import com.parkio.parking.availability.AvailabilityFreshness;
import com.parkio.parking.availability.AvailabilityReason;
import com.parkio.parking.availability.AvailabilityState;
import com.parkio.parking.availability.assessment.AvailabilityAssessment;
import com.parkio.parking.availability.evaluation.AvailabilityEvaluationContext;
import com.parkio.parking.availability.evidence.AvailabilityEvidence;
import com.parkio.parking.availability.expiration.AvailabilityExpiration;
import com.parkio.parking.availability.score.AvailabilityScore;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Reference v1 availability decay and occupancy policy.
 *
 * <p>Uses only evidence fields present on {@code ParkingSpot} today. Does not mutate
 * publication state or TTL.
 */
public final class DefaultAvailabilityPolicy implements AvailabilityPolicy {

    private final AvailabilityPolicyConfig config;

    public DefaultAvailabilityPolicy() {
        this(AvailabilityPolicyConfig.referenceV1());
    }

    public DefaultAvailabilityPolicy(AvailabilityPolicyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public AvailabilityEvaluation evaluate(AvailabilityEvidence evidence, AvailabilityEvaluationContext context) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        Instant evaluatedAt = context.evaluatedAt();
        AvailabilityExpiration expiration = AvailabilityExpiration.of(evidence.expiresAt(), evaluatedAt);

        AvailabilityAssessment assessment = assess(evidence, context, expiration);
        return new AvailabilityEvaluation(
                evidence.parkingSpotId(),
                assessment.state(),
                assessment.score(),
                assessment.freshness(),
                assessment.primaryReason(),
                assessment.reasons(),
                expiration,
                context.policyVersion(),
                evaluatedAt);
    }

    AvailabilityAssessment assess(
            AvailabilityEvidence evidence,
            AvailabilityEvaluationContext context,
            AvailabilityExpiration expiration) {
        Instant evaluatedAt = context.evaluatedAt();
        Set<AvailabilityReason> reasons = new LinkedHashSet<>();

        if (evidence.isPendingModeration() || !evidence.isPublished()) {
            AvailabilityReason primary = evidence.isPendingModeration()
                    ? AvailabilityReason.STATUS_PENDING_MODERATION
                    : AvailabilityReason.NOT_PUBLISHED;
            reasons.add(primary);
            return assessment(
                    AvailabilityState.UNKNOWN,
                    AvailabilityScore.of(0),
                    AvailabilityFreshness.FRESH,
                    primary,
                    reasons,
                    0,
                    0);
        }

        if (evidence.status() == ParkingSpotStatus.FILLED) {
            reasons.add(AvailabilityReason.STATUS_FILLED);
            return assessment(
                    AvailabilityState.UNAVAILABLE,
                    AvailabilityScore.of(0),
                    AvailabilityFreshness.EXPIRED,
                    AvailabilityReason.STATUS_FILLED,
                    reasons,
                    0,
                    10_000);
        }

        if (evidence.status() == ParkingSpotStatus.REJECTED || evidence.status() == ParkingSpotStatus.REVIEW_FAILED) {
            reasons.add(evidence.status() == ParkingSpotStatus.REJECTED
                    ? AvailabilityReason.STATUS_REJECTED
                    : AvailabilityReason.STATUS_TERMINAL);
            return assessment(
                    AvailabilityState.UNAVAILABLE,
                    AvailabilityScore.of(0),
                    AvailabilityFreshness.EXPIRED,
                    reasons.iterator().next(),
                    reasons,
                    0,
                    10_000);
        }

        if (evidence.status() == ParkingSpotStatus.EXPIRED || expiration.expired() || evidence.isTimeExpired(evaluatedAt)) {
            reasons.add(expiration.expired() || evidence.isTimeExpired(evaluatedAt)
                    ? AvailabilityReason.TIME_EXPIRED
                    : AvailabilityReason.STATUS_EXPIRED);
            return assessment(
                    AvailabilityState.EXPIRED,
                    AvailabilityScore.of(0),
                    AvailabilityFreshness.EXPIRED,
                    AvailabilityReason.TIME_EXPIRED,
                    reasons,
                    0,
                    10_000);
        }

        WindowMetrics window = windowMetrics(evidence, context, evaluatedAt);
        reasons.addAll(window.reasons());

        if (evidence.filledReportCount() > 0) {
            reasons.add(AvailabilityReason.FILLED_REPORTS);
        }
        if (evidence.verificationCount() > 0) {
            reasons.add(AvailabilityReason.COMMUNITY_VERIFIED);
        }
        if (confidenceBasisPoints(evidence) < AvailabilityPolicyConfig.LOW_CONFIDENCE_BPS) {
            reasons.add(AvailabilityReason.LOW_CONFIDENCE);
        }

        AvailabilityState state = classifyPublishedState(evidence, window);
        AvailabilityFreshness freshness = classifyFreshness(window.elapsedBasisPoints());
        AvailabilityReason primaryReason = primaryReasonFor(state, window, evidence, freshness);
        reasons.add(primaryReason);

        int score = computeScore(evidence, window);
        return assessment(state, AvailabilityScore.of(score), freshness, primaryReason, reasons,
                window.remainingBasisPoints(), window.elapsedBasisPoints());
    }

    private AvailabilityState classifyPublishedState(AvailabilityEvidence evidence, WindowMetrics window) {
        if (evidence.status() == ParkingSpotStatus.SUSPICIOUS || evidence.filledReportCount() > 0) {
            return AvailabilityState.LIKELY_OCCUPIED;
        }
        if (window.remainingBasisPoints() >= AvailabilityPolicyConfig.AVAILABLE_REMAINING_BPS) {
            return AvailabilityState.AVAILABLE;
        }
        if (window.remainingBasisPoints() >= AvailabilityPolicyConfig.LIKELY_AVAILABLE_REMAINING_BPS) {
            return AvailabilityState.LIKELY_AVAILABLE;
        }
        if (window.remainingBasisPoints() >= AvailabilityPolicyConfig.UNKNOWN_REMAINING_BPS) {
            return AvailabilityState.UNKNOWN;
        }
        return AvailabilityState.LIKELY_OCCUPIED;
    }

    private AvailabilityFreshness classifyFreshness(int elapsedBasisPoints) {
        if (elapsedBasisPoints <= AvailabilityPolicyConfig.FRESH_ELAPSED_BPS) {
            return AvailabilityFreshness.FRESH;
        }
        if (elapsedBasisPoints <= AvailabilityPolicyConfig.AGING_ELAPSED_BPS) {
            return AvailabilityFreshness.AGING;
        }
        if (elapsedBasisPoints <= AvailabilityPolicyConfig.STALE_ELAPSED_BPS) {
            return AvailabilityFreshness.STALE;
        }
        return AvailabilityFreshness.EXPIRED;
    }

    private AvailabilityReason primaryReasonFor(
            AvailabilityState state,
            WindowMetrics window,
            AvailabilityEvidence evidence,
            AvailabilityFreshness freshness) {
        return switch (state) {
            case AVAILABLE -> window.remainingBasisPoints() >= AvailabilityPolicyConfig.AVAILABLE_REMAINING_BPS
                    ? AvailabilityReason.TTL_REMAINING_HIGH
                    : AvailabilityReason.FRESH_PUBLICATION;
            case LIKELY_AVAILABLE -> AvailabilityReason.TTL_REMAINING_MODERATE;
            case UNKNOWN -> evidence.isPendingModeration()
                    ? AvailabilityReason.STATUS_PENDING_MODERATION
                    : AvailabilityReason.UNKNOWN_SIGNALS;
            case LIKELY_OCCUPIED -> evidence.status() == ParkingSpotStatus.SUSPICIOUS
                    ? AvailabilityReason.STATUS_SUSPICIOUS
                    : evidence.filledReportCount() > 0
                            ? AvailabilityReason.FILLED_REPORTS
                            : AvailabilityReason.TTL_REMAINING_LOW;
            case UNAVAILABLE -> AvailabilityReason.STATUS_FILLED;
            case EXPIRED -> freshness == AvailabilityFreshness.EXPIRED
                    ? AvailabilityReason.TIME_EXPIRED
                    : AvailabilityReason.STATUS_EXPIRED;
        };
    }

    private int computeScore(AvailabilityEvidence evidence, WindowMetrics window) {
        int score = AvailabilityPolicyConfig.divideHalfUp(
                (long) window.remainingBasisPoints() * 100L, 10_000L);
        if (evidence.verificationCount() > 0) {
            score += AvailabilityPolicyConfig.VERIFICATION_SCORE_BOOST;
        }
        score -= evidence.filledReportCount() * AvailabilityPolicyConfig.FILLED_REPORT_SCORE_PENALTY;
        if (confidenceBasisPoints(evidence) < AvailabilityPolicyConfig.LOW_CONFIDENCE_BPS) {
            score -= 15;
        }
        if (evidence.status() == ParkingSpotStatus.SUSPICIOUS) {
            score -= 25;
        }
        return AvailabilityPolicyConfig.clampScore(score);
    }

    private WindowMetrics windowMetrics(
            AvailabilityEvidence evidence,
            AvailabilityEvaluationContext context,
            Instant evaluatedAt) {
        Set<AvailabilityReason> reasons = new LinkedHashSet<>();
        Instant activatedAt = evidence.activatedAt();
        Instant expiresAt = evidence.expiresAt();

        Duration totalWindow = expiresAt != null && activatedAt != null
                ? Duration.between(activatedAt, expiresAt)
                : context.advertisedLifetime();
        if (totalWindow.isZero() || totalWindow.isNegative()) {
            totalWindow = context.advertisedLifetime();
        }

        Duration elapsed = activatedAt != null
                ? Duration.between(activatedAt, evaluatedAt)
                : Duration.between(evidence.createdAt(), evaluatedAt);
        if (elapsed.isNegative()) {
            elapsed = Duration.ZERO;
        }

        Duration remaining = expiresAt != null
                ? Duration.between(evaluatedAt, expiresAt)
                : totalWindow.minus(elapsed);
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }

        int elapsedBps = config.clampBasisPoints(
                AvailabilityPolicyConfig.divideHalfUp(elapsed.toMillis() * 10_000L, totalWindow.toMillis()));
        int remainingBps = config.clampBasisPoints(
                AvailabilityPolicyConfig.divideHalfUp(remaining.toMillis() * 10_000L, totalWindow.toMillis()));

        if (remainingBps >= AvailabilityPolicyConfig.AVAILABLE_REMAINING_BPS) {
            reasons.add(AvailabilityReason.TTL_REMAINING_HIGH);
        } else if (remainingBps >= AvailabilityPolicyConfig.LIKELY_AVAILABLE_REMAINING_BPS) {
            reasons.add(AvailabilityReason.TTL_REMAINING_MODERATE);
        } else if (remainingBps >= AvailabilityPolicyConfig.UNKNOWN_REMAINING_BPS) {
            reasons.add(AvailabilityReason.TTL_REMAINING_LOW);
        }

        return new WindowMetrics(elapsedBps, remainingBps, reasons);
    }

    private static int confidenceBasisPoints(AvailabilityEvidence evidence) {
        return (int) Math.round(evidence.confidenceScore() * 10_000.0);
    }

    private static AvailabilityAssessment assessment(
            AvailabilityState state,
            AvailabilityScore score,
            AvailabilityFreshness freshness,
            AvailabilityReason primaryReason,
            Set<AvailabilityReason> reasons,
            int remainingBasisPoints,
            int elapsedBasisPoints) {
        return new AvailabilityAssessment(
                state,
                score,
                freshness,
                primaryReason,
                reasons,
                remainingBasisPoints,
                elapsedBasisPoints);
    }

    private record WindowMetrics(int elapsedBasisPoints, int remainingBasisPoints, Set<AvailabilityReason> reasons) {}
}
