package com.parkio.parking.exposure;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Pure deterministic adaptive exposure engine (shadow-only). */
public final class ExposureEngine {

    public ExposureEvaluation evaluate(ExposureEvidence evidence, ExposureEvaluationContext context) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        ExposurePolicyConfig policy = policyFor(context.policyVersion());

        EligibilityDecision eligibility = eligibilityFor(evidence);
        if (eligibility.eligibility() != ExposureEligibility.ELIGIBLE) {
            return ineligibleEvaluation(evidence, eligibility, policy, context);
        }

        int distanceScore = distanceContribution(evidence, policy);
        int freshnessScore = freshnessContribution(evidence, context, policy);
        int availabilityScore = availabilityContribution(evidence, policy);
        int vehicleScore = vehicleContribution(evidence, policy);
        int publicationScore = publicationContribution(evidence, policy);
        int trustScore = trustContribution(evidence, policy);

        Set<ExposureScoreComponent> components = Set.of(
                new ExposureScoreComponent("DISTANCE", distanceScore),
                new ExposureScoreComponent("FRESHNESS", freshnessScore),
                new ExposureScoreComponent("AVAILABILITY", availabilityScore),
                new ExposureScoreComponent("VEHICLE", vehicleScore),
                new ExposureScoreComponent("PUBLICATION", publicationScore),
                new ExposureScoreComponent("TRUST", trustScore));

        int total = Math.min(
                distanceScore + freshnessScore + availabilityScore + vehicleScore + publicationScore + trustScore,
                ExposurePolicyConfig.MAX_TOTAL_SCORE);

        ExposureDisposition disposition = dispositionFor(total, policy);
        return new ExposureEvaluation(
                evidence,
                ExposureEligibility.ELIGIBLE,
                eligibility.primaryReason(),
                disposition,
                new ExposureScore(total, components),
                decisiveReason(disposition, total),
                eligibility.reasons(),
                policy.policyVersion(),
                context.evaluatedAt());
    }

    private static ExposurePolicyConfig policyFor(String version) {
        if (ExposurePolicyConfig.POLICY_VERSION.equals(version)) {
            return ExposurePolicyConfig.referenceV1();
        }
        throw new UnsupportedExposurePolicyVersionException("Unsupported exposure policy version: " + version);
    }

    private record EligibilityDecision(
            ExposureEligibility eligibility,
            ExposureEligibilityReason primaryReason,
            Set<ExposureEligibilityReason> reasons) {
    }

    private static EligibilityDecision eligibilityFor(ExposureEvidence evidence) {
        Set<ExposureEligibilityReason> reasons = new LinkedHashSet<>();
        if (!evidence.searchableVisible()) {
            reasons.add(ExposureEligibilityReason.STATUS_NOT_PUBLISHED);
            return new EligibilityDecision(
                    ExposureEligibility.INELIGIBLE_NOT_PUBLISHED,
                    ExposureEligibilityReason.STATUS_NOT_PUBLISHED,
                    reasons);
        }
        if (evidence.availabilityState() == ExposureAvailabilityState.EXPIRED) {
            reasons.add(ExposureEligibilityReason.EXPIRED);
            return new EligibilityDecision(
                    ExposureEligibility.INELIGIBLE_EXPIRED,
                    ExposureEligibilityReason.EXPIRED,
                    reasons);
        }
        if (evidence.availabilityState() == ExposureAvailabilityState.UNAVAILABLE) {
            reasons.add(ExposureEligibilityReason.AVAILABILITY_UNAVAILABLE);
            return new EligibilityDecision(
                    ExposureEligibility.INELIGIBLE_AVAILABILITY,
                    ExposureEligibilityReason.AVAILABILITY_UNAVAILABLE,
                    reasons);
        }
        if (evidence.publicationQuality() == ExposurePublicationQuality.OTHER) {
            reasons.add(ExposureEligibilityReason.STATUS_TERMINAL);
            return new EligibilityDecision(
                    ExposureEligibility.INELIGIBLE_TERMINAL_STATUS,
                    ExposureEligibilityReason.STATUS_TERMINAL,
                    reasons);
        }
        if (evidence.vehicleMatch() == ExposureVehicleMatch.MISMATCH) {
            reasons.add(ExposureEligibilityReason.VEHICLE_MISMATCH);
            return new EligibilityDecision(
                    ExposureEligibility.INELIGIBLE_VEHICLE_MISMATCH,
                    ExposureEligibilityReason.VEHICLE_MISMATCH,
                    reasons);
        }
        if (evidence.distanceMeters() < 0) {
            reasons.add(ExposureEligibilityReason.MISSING_DISTANCE);
            return new EligibilityDecision(
                    ExposureEligibility.INSUFFICIENT_EVIDENCE,
                    ExposureEligibilityReason.MISSING_DISTANCE,
                    reasons);
        }
        if (evidence.publicationQuality() == ExposurePublicationQuality.VERIFIED) {
            reasons.add(ExposureEligibilityReason.PUBLISHED_VERIFIED);
        } else {
            reasons.add(ExposureEligibilityReason.PUBLISHED_ACTIVE);
        }
        return new EligibilityDecision(
                ExposureEligibility.ELIGIBLE,
                reasons.iterator().next(),
                reasons);
    }

    private static ExposureEvaluation ineligibleEvaluation(
            ExposureEvidence evidence,
            EligibilityDecision eligibility,
            ExposurePolicyConfig policy,
            ExposureEvaluationContext context) {
        return new ExposureEvaluation(
                evidence,
                eligibility.eligibility(),
                eligibility.primaryReason(),
                ExposureDisposition.INELIGIBLE,
                new ExposureScore(0, Set.of()),
                "INELIGIBLE_" + eligibility.primaryReason().name(),
                eligibility.reasons(),
                policy.policyVersion(),
                context.evaluatedAt());
    }

    private static int distanceContribution(ExposureEvidence evidence, ExposurePolicyConfig policy) {
        int radius = Math.max(evidence.requestRadiusMeters(), 1);
        int distance = Math.min(evidence.distanceMeters(), radius);
        long numerator = (long) (radius - distance) * policy.distanceMaxContribution();
        return (int) ((numerator + radius / 2L) / radius);
    }

    private static int freshnessContribution(
            ExposureEvidence evidence,
            ExposureEvaluationContext context,
            ExposurePolicyConfig policy) {
        return switch (evidence.freshnessBand()) {
            case "VERY_FRESH" -> policy.freshnessMaxContribution();
            case "FRESH" -> multiplyBasisPoints(policy.freshnessMaxContribution(), 8_000);
            case "AGING" -> multiplyBasisPoints(policy.freshnessMaxContribution(), 5_000);
            case "STALE" -> multiplyBasisPoints(policy.freshnessMaxContribution(), 2_500);
            default -> multiplyBasisPoints(policy.freshnessMaxContribution(), 1_000);
        };
    }

    private static int availabilityContribution(ExposureEvidence evidence, ExposurePolicyConfig policy) {
        return switch (evidence.availabilityState()) {
            case AVAILABLE -> policy.availabilityMaxContribution();
            case LIKELY_AVAILABLE -> multiplyBasisPoints(policy.availabilityMaxContribution(), 7_500);
            case UNKNOWN -> multiplyBasisPoints(policy.availabilityMaxContribution(), 4_000);
            case LIKELY_OCCUPIED -> multiplyBasisPoints(policy.availabilityMaxContribution(), 1_500);
            case UNAVAILABLE, EXPIRED -> 0;
        };
    }

    private static int vehicleContribution(ExposureEvidence evidence, ExposurePolicyConfig policy) {
        return switch (evidence.vehicleMatch()) {
            case MATCH -> policy.vehicleMaxContribution();
            case NOT_REQUESTED, UNKNOWN -> multiplyBasisPoints(policy.vehicleMaxContribution(), 5_000);
            case MISMATCH -> 0;
        };
    }

    private static int publicationContribution(ExposureEvidence evidence, ExposurePolicyConfig policy) {
        return switch (evidence.publicationQuality()) {
            case VERIFIED -> policy.publicationMaxContribution();
            case ACTIVE -> multiplyBasisPoints(policy.publicationMaxContribution(), 7_000);
            case OTHER -> 0;
        };
    }

    private static int trustContribution(ExposureEvidence evidence, ExposurePolicyConfig policy) {
        if (policy.trustMaxContribution() == 0) {
            return 0;
        }
        return switch (evidence.trustLevel()) {
            case HIGH -> policy.trustMaxContribution();
            case MEDIUM -> multiplyBasisPoints(policy.trustMaxContribution(), 6_000);
            case LOW -> multiplyBasisPoints(policy.trustMaxContribution(), 3_000);
            case UNKNOWN -> multiplyBasisPoints(policy.trustMaxContribution(), 5_000);
        };
    }

    private static ExposureDisposition dispositionFor(int total, ExposurePolicyConfig policy) {
        if (total >= policy.prioritizeThreshold()) {
            return ExposureDisposition.PRIORITIZE;
        }
        if (total >= policy.standardThreshold()) {
            return ExposureDisposition.STANDARD;
        }
        if (total >= policy.deprioritizeThreshold()) {
            return ExposureDisposition.DEPRIORITIZE;
        }
        if (total >= policy.minimumEligibleScore()) {
            return ExposureDisposition.HOLD;
        }
        return ExposureDisposition.HOLD;
    }

    private static String decisiveReason(ExposureDisposition disposition, int total) {
        return disposition.name() + "_SCORE_" + ExposureShadowOrdering.scoreBand(total);
    }

    private static int multiplyBasisPoints(int value, int multiplier) {
        long raw = (long) value * multiplier;
        return (int) ((raw + ExposurePolicyConfig.BASIS_POINTS / 2L) / ExposurePolicyConfig.BASIS_POINTS);
    }
}
