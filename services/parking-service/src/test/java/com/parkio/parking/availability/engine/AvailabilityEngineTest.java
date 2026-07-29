package com.parkio.parking.availability.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.availability.AvailabilityEvaluation;
import com.parkio.parking.availability.AvailabilityFreshness;
import com.parkio.parking.availability.AvailabilityReason;
import com.parkio.parking.availability.AvailabilityState;
import com.parkio.parking.availability.evaluation.AvailabilityEvaluationContext;
import com.parkio.parking.availability.evidence.AvailabilityEvidence;
import com.parkio.parking.availability.policy.AvailabilityPolicyConfig;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AvailabilityEngineTest {

    private static final Duration ACTIVE = Duration.ofMinutes(10);
    private static final Instant BASE = Instant.parse("2026-07-28T10:00:00Z");
    private static final UUID SPOT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AvailabilityEngine engine = new AvailabilityEngine();

    @Test
    void freshPublishedSpotIsAvailableWithHighScore() {
        AvailabilityEvidence evidence = published(
                ParkingSpotStatus.ACTIVE,
                BASE,
                BASE.plus(ACTIVE),
                0,
                0,
                1.0);
        AvailabilityEvaluation evaluation = evaluate(evidence, BASE.plusSeconds(30));

        assertThat(evaluation.state()).isEqualTo(AvailabilityState.AVAILABLE);
        assertThat(evaluation.freshness()).isEqualTo(AvailabilityFreshness.FRESH);
        assertThat(evaluation.score().value()).isGreaterThanOrEqualTo(70);
        assertThat(evaluation.primaryReason()).isEqualTo(AvailabilityReason.TTL_REMAINING_HIGH);
    }

    @Test
    void agingSpotDecaysTowardUnknown() {
        AvailabilityEvidence evidence = published(
                ParkingSpotStatus.VERIFIED,
                BASE,
                BASE.plus(ACTIVE),
                1,
                0,
                1.0);
        AvailabilityEvaluation midLife = evaluate(evidence, BASE.plus(Duration.ofMinutes(6)));
        AvailabilityEvaluation lateLife = evaluate(evidence, BASE.plus(Duration.ofMinutes(8)).plusSeconds(30));

        assertThat(midLife.state()).isIn(AvailabilityState.LIKELY_AVAILABLE, AvailabilityState.UNKNOWN);
        assertThat(lateLife.state()).isIn(AvailabilityState.UNKNOWN, AvailabilityState.LIKELY_OCCUPIED);
        assertThat(lateLife.score().value()).isLessThan(midLife.score().value());
    }

    @Test
    void expirationMarksSpotExpiredAtBoundary() {
        Instant activated = BASE;
        Instant expires = BASE.plus(ACTIVE);
        AvailabilityEvidence evidence = published(ParkingSpotStatus.ACTIVE, activated, expires, 0, 0, 1.0);

        AvailabilityEvaluation before = evaluate(evidence, expires.minusSeconds(1));
        AvailabilityEvaluation at = evaluate(evidence, expires);
        AvailabilityEvaluation after = evaluate(evidence, expires.plusSeconds(1));

        assertThat(before.state()).isNotEqualTo(AvailabilityState.EXPIRED);
        assertThat(at.state()).isEqualTo(AvailabilityState.EXPIRED);
        assertThat(after.state()).isEqualTo(AvailabilityState.EXPIRED);
        assertThat(after.expiration().expired()).isTrue();
        assertThat(after.primaryReason()).isEqualTo(AvailabilityReason.TIME_EXPIRED);
    }

    @Test
    void filledSpotIsUnavailableRegardlessOfTtl() {
        AvailabilityEvidence evidence = published(
                ParkingSpotStatus.FILLED,
                BASE,
                BASE.plus(ACTIVE),
                0,
                2,
                0.2);
        AvailabilityEvaluation evaluation = evaluate(evidence, BASE.plus(Duration.ofMinutes(1)));

        assertThat(evaluation.state()).isEqualTo(AvailabilityState.UNAVAILABLE);
        assertThat(evaluation.score().value()).isZero();
    }

    @Test
    void suspiciousSpotWithFilledReportsIsLikelyOccupied() {
        AvailabilityEvidence evidence = published(
                ParkingSpotStatus.SUSPICIOUS,
                BASE,
                BASE.plus(ACTIVE),
                0,
                1,
                0.4);
        AvailabilityEvaluation evaluation = evaluate(evidence, BASE.plus(Duration.ofMinutes(2)));

        assertThat(evaluation.state()).isEqualTo(AvailabilityState.LIKELY_OCCUPIED);
        assertThat(evaluation.reasons()).contains(AvailabilityReason.FILLED_REPORTS, AvailabilityReason.STATUS_SUSPICIOUS);
    }

    @Test
    void pendingModerationSpotIsUnknownWithoutScoreBoost() {
        AvailabilityEvidence evidence = new AvailabilityEvidence(
                SPOT_ID,
                ParkingSpotStatus.PENDING_VALIDATION,
                LegalStatus.LEGAL,
                BASE,
                null,
                null,
                0,
                0,
                1.0);
        AvailabilityEvaluation evaluation = evaluate(evidence, BASE.plus(Duration.ofMinutes(1)));

        assertThat(evaluation.state()).isEqualTo(AvailabilityState.UNKNOWN);
        assertThat(evaluation.score().value()).isZero();
        assertThat(evaluation.reasons()).contains(AvailabilityReason.STATUS_PENDING_MODERATION);
    }

    @Test
    void evaluationIsDeterministicForSameInputs() {
        AvailabilityEvidence evidence = published(
                ParkingSpotStatus.VERIFIED,
                BASE,
                BASE.plus(ACTIVE),
                2,
                0,
                0.9);
        Instant at = BASE.plus(Duration.ofMinutes(4));

        AvailabilityEvaluation first = evaluate(evidence, at);
        AvailabilityEvaluation second = evaluate(evidence, at);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void clockInjectionControlsFreshnessWithoutSystemClock() {
        AvailabilityEvidence evidence = published(
                ParkingSpotStatus.ACTIVE,
                BASE,
                BASE.plus(ACTIVE),
                0,
                0,
                1.0);

        AvailabilityEvaluation fresh = evaluate(evidence, BASE.plus(Duration.ofMinutes(1)));
        AvailabilityEvaluation stale = evaluate(evidence, BASE.plus(Duration.ofMinutes(9)));

        assertThat(fresh.freshness()).isEqualTo(AvailabilityFreshness.FRESH);
        assertThat(stale.freshness()).isIn(AvailabilityFreshness.STALE, AvailabilityFreshness.EXPIRED);
    }

    @Test
    void verificationBoostsScoreWithinPolicyBoundaries() {
        AvailabilityEvidence without = published(ParkingSpotStatus.ACTIVE, BASE, BASE.plus(ACTIVE), 0, 0, 1.0);
        AvailabilityEvidence with = published(ParkingSpotStatus.VERIFIED, BASE, BASE.plus(ACTIVE), 2, 0, 1.0);
        Instant at = BASE.plus(Duration.ofMinutes(5));

        int baseScore = evaluate(without, at).score().value();
        int verifiedScore = evaluate(with, at).score().value();

        assertThat(verifiedScore - baseScore)
                .isEqualTo(AvailabilityPolicyConfig.VERIFICATION_SCORE_BOOST);
    }

    private static AvailabilityEvaluation evaluate(AvailabilityEvidence evidence, Instant evaluatedAt) {
        AvailabilityEvaluationContext context = new AvailabilityEvaluationContext(
                evaluatedAt,
                AvailabilityPolicyConfig.POLICY_VERSION,
                ACTIVE);
        return new AvailabilityEngine().evaluate(evidence, context);
    }

    private static AvailabilityEvidence published(
            ParkingSpotStatus status,
            Instant activatedAt,
            Instant expiresAt,
            int verificationCount,
            int filledReportCount,
            double confidence) {
        return new AvailabilityEvidence(
                SPOT_ID,
                status,
                LegalStatus.LEGAL,
                BASE,
                activatedAt,
                expiresAt,
                verificationCount,
                filledReportCount,
                confidence);
    }
}