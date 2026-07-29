package com.parkio.parking.trust;

import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeReason;
import java.time.Instant;
import java.util.Objects;

/** Pure deterministic trust-update engine. */
public final class TrustEngine {

    public TrustEvaluation evaluate(TrustSnapshot previous, TrustEvidence evidence, TrustEvaluationContext context) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        TrustPolicyConfig policy = policyFor(context.trustPolicyVersion());
        ensureCompatible(previous, evidence, context, policy);

        if (evidence.eligibility() != TrustEvidence.Eligibility.ELIGIBLE) {
            TrustSnapshot unchanged = new TrustSnapshot(
                    previous.subject(),
                    previous.domain(),
                    policy.policyVersion(),
                    context.snapshotSchemaVersion(),
                    previous.score(),
                    previous.confidence(),
                    previous.positiveEvidenceMass(),
                    previous.negativeEvidenceMass(),
                    previous.effectiveEvidenceCount(),
                    previous.level(),
                    context.evaluatedAt());
            return new TrustEvaluation(
                    evidence,
                    previous,
                    unchanged,
                    0,
                    0,
                    TrustEvaluation.Direction.NEUTRAL,
                    evidence.eligibility().name(),
                    policy.policyVersion(),
                    context.evaluatedAt());
        }

        int signedWeight = signedWeight(evidence, policy);
        int positiveDelta = Math.max(0, signedWeight);
        int negativeDelta = Math.max(0, -signedWeight);
        int nextPositiveMass = previous.positiveEvidenceMass() + positiveDelta;
        int nextNegativeMass = previous.negativeEvidenceMass() + negativeDelta;
        int nextEvidenceCount = previous.effectiveEvidenceCount() + (signedWeight == 0 ? 0 : 1);
        TrustScore nextScore = score(nextPositiveMass, nextNegativeMass, policy);
        TrustConfidence nextConfidence = confidence(nextPositiveMass + nextNegativeMass, policy);
        TrustSnapshot.Level nextLevel = level(nextScore, nextConfidence, nextEvidenceCount, policy);
        TrustSnapshot resulting = new TrustSnapshot(
                previous.subject(),
                previous.domain(),
                policy.policyVersion(),
                context.snapshotSchemaVersion(),
                nextScore,
                nextConfidence,
                nextPositiveMass,
                nextNegativeMass,
                nextEvidenceCount,
                nextLevel,
                context.evaluatedAt());
        return new TrustEvaluation(
                evidence,
                previous,
                resulting,
                positiveDelta,
                negativeDelta,
                signedWeight > 0 ? TrustEvaluation.Direction.POSITIVE
                        : signedWeight < 0 ? TrustEvaluation.Direction.NEGATIVE : TrustEvaluation.Direction.NEUTRAL,
                decisiveReason(evidence, signedWeight),
                policy.policyVersion(),
                context.evaluatedAt());
    }

    public TrustSnapshot initialSnapshot(
            TrustSubject subject,
            TrustDomain domain,
            TrustEvaluationContext context) {
        TrustPolicyConfig policy = policyFor(context.trustPolicyVersion());
        return TrustSnapshot.initial(
                subject,
                domain,
                policy.policyVersion(),
                context.snapshotSchemaVersion(),
                score(0, 0, policy),
                TrustConfidence.of(0),
                TrustSnapshot.Level.UNKNOWN,
                context.evaluatedAt());
    }

    private static TrustPolicyConfig policyFor(String version) {
        if (TrustPolicyConfig.POLICY_VERSION.equals(version)) {
            return TrustPolicyConfig.referenceV1();
        }
        throw new UnsupportedTrustPolicyVersionException("Unsupported trust policy version: " + version);
    }

    private static void ensureCompatible(
            TrustSnapshot previous,
            TrustEvidence evidence,
            TrustEvaluationContext context,
            TrustPolicyConfig policy) {
        if (!previous.subject().equals(evidence.subject())) {
            throw new IllegalArgumentException("snapshot subject must match evidence subject");
        }
        if (previous.domain() != evidence.domain()) {
            throw new IllegalArgumentException("snapshot domain must match evidence domain");
        }
        if (!policy.policyVersion().equals(previous.trustPolicyVersion())
                && previous.effectiveEvidenceCount() > 0) {
            throw new UnsupportedTrustPolicyVersionException(
                    "Snapshot policy " + previous.trustPolicyVersion()
                            + " cannot be incrementally updated by " + policy.policyVersion());
        }
        if (context.evaluatedAt().isBefore(previous.lastEvaluatedAt() == null
                ? Instant.EPOCH : previous.lastEvaluatedAt())) {
            throw new IllegalArgumentException("trust evaluations must be replayed in canonical order");
        }
    }

    private static int signedWeight(TrustEvidence evidence, TrustPolicyConfig policy) {
        int baseWeight = switch (evidence.outcomeClassification()) {
            case CONFIRMED_CORRECT -> policy.confirmedCorrectWeight();
            case LIKELY_CORRECT -> policy.likelyCorrectWeight();
            case CONFIRMED_INCORRECT -> policy.confirmedIncorrectWeight();
            case LIKELY_INCORRECT -> policy.likelyIncorrectWeight();
            case UNKNOWN, EXPIRED_WITHOUT_EVIDENCE -> 0;
        };
        if (baseWeight == 0) {
            return 0;
        }
        boolean negative = isNegative(evidence);
        if (negative && evidence.outcomeConfidence() < policy.minimumNegativeConfidence()) {
            return 0;
        }
        int attributionMultiplier = switch (evidence.attributionQuality()) {
            case DIRECT -> policy.directAttributionMultiplier();
            case STRONG -> policy.strongAttributionMultiplier();
            case PARTIAL -> policy.partialAttributionMultiplier();
            case AMBIGUOUS, NONE -> 0;
        };
        int confidenceMultiplier = confidenceMultiplier(evidence.outcomeConfidence(), policy);
        int adjusted = multiplyBasisPoints(baseWeight, attributionMultiplier);
        adjusted = multiplyBasisPoints(adjusted, confidenceMultiplier);
        adjusted = Math.min(adjusted, policy.maxEvidenceImpact());
        return negative ? -adjusted : adjusted;
    }

    private static boolean isNegative(TrustEvidence evidence) {
        return switch (evidence.primaryOutcomeReason()) {
            case NEGATIVE_VERIFICATION, MODERATOR_REJECTION -> true;
            case AI_REJECTION, REVIEW_FAILED -> evidence.outcomeClassification() == OutcomeClassification.CONFIRMED_INCORRECT;
            case COMMUNITY_FILLED_REPORTS, TIME_EXPIRED, TIME_EXPIRED_NO_EVIDENCE,
                    VALIDATION_WINDOW_OPEN, INSUFFICIENT_EVIDENCE, TERMINAL_STATUS,
                    COMMUNITY_CLAIM_CONFIRMED, MULTIPLE_AVAILABLE_VERIFICATIONS,
                    SINGLE_AVAILABLE_VERIFICATION, NOT_YET_PUBLISHED -> false;
        };
    }

    private static int confidenceMultiplier(int outcomeConfidence, TrustPolicyConfig policy) {
        if (outcomeConfidence >= 85) {
            return policy.highConfidenceMultiplier();
        }
        if (outcomeConfidence >= 70) {
            return policy.mediumConfidenceMultiplier();
        }
        return policy.lowConfidenceMultiplier();
    }

    private static TrustScore score(int positiveMass, int negativeMass, TrustPolicyConfig policy) {
        int numerator = policy.priorPositiveMass() + positiveMass;
        int denominator = policy.priorPositiveMass() + policy.priorNegativeMass() + positiveMass + negativeMass;
        return TrustScore.of(divideRounded(numerator * TrustPolicyConfig.BASIS_POINTS, denominator));
    }

    private static TrustConfidence confidence(int totalMass, TrustPolicyConfig policy) {
        if (totalMass <= 0) {
            return TrustConfidence.of(0);
        }
        return TrustConfidence.of(Math.min(
                TrustPolicyConfig.BASIS_POINTS,
                divideRounded(totalMass * TrustPolicyConfig.BASIS_POINTS, policy.confidenceSaturationMass())));
    }

    private static TrustSnapshot.Level level(
            TrustScore score,
            TrustConfidence confidence,
            int effectiveEvidenceCount,
            TrustPolicyConfig policy) {
        if (effectiveEvidenceCount == 0) {
            return TrustSnapshot.Level.UNKNOWN;
        }
        if (confidence.basisPoints() < policy.lowConfidenceThreshold()) {
            return TrustSnapshot.Level.LOW_CONFIDENCE;
        }
        if (confidence.basisPoints() < policy.establishedConfidenceThreshold()
                || between(score.basisPoints(), 4_500, 5_500)) {
            return TrustSnapshot.Level.DEVELOPING;
        }
        if (confidence.basisPoints() >= policy.highConfidenceThreshold()
                && effectiveEvidenceCount >= policy.highConfidenceMinimumEvidenceCount()
                && !between(score.basisPoints(), 4_000, 6_000)) {
            return TrustSnapshot.Level.HIGH_CONFIDENCE;
        }
        return TrustSnapshot.Level.ESTABLISHED;
    }

    private static String decisiveReason(TrustEvidence evidence, int signedWeight) {
        if (signedWeight == 0) {
            return "NO_LEARNING_" + evidence.primaryOutcomeReason().name();
        }
        return (signedWeight > 0 ? "POSITIVE_" : "NEGATIVE_") + evidence.primaryOutcomeReason().name();
    }

    private static int multiplyBasisPoints(int value, int multiplier) {
        long raw = (long) value * multiplier;
        return divideRounded(raw, TrustPolicyConfig.BASIS_POINTS);
    }

    private static int divideRounded(long numerator, long denominator) {
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        return (int) ((numerator + (denominator / 2)) / denominator);
    }

    private static boolean between(int value, int lowerInclusive, int upperInclusive) {
        return value >= lowerInclusive && value <= upperInclusive;
    }
}

