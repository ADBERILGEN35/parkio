package com.parkio.parking.fraud;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure deterministic fraud-risk engine. */
public final class FraudEngine {

    public FraudEvaluation evaluate(FraudFeatureVector features, FraudEvaluationContext context) {
        Objects.requireNonNull(features, "features");
        Objects.requireNonNull(context, "context");
        FraudPolicyConfig policy = policyFor(context.policyVersion());
        if (features.domain() != FraudDomain.CONTRIBUTION_INTEGRITY) {
            return unsupportedDomain(features, context, policy);
        }

        int evidenceCount = features.eligibleContributionCount();
        if (evidenceCount < policy.minimumEvidenceVolume()) {
            return insufficientEvidence(features, context, policy, "MINIMUM_EVIDENCE_NOT_MET");
        }

        int incorrectContribution = Math.min(
                policy.maxCategoryContribution(),
                features.directConfirmedIncorrectCount() * policy.confirmedIncorrectWeight()
                        + features.likelyIncorrectCount() * policy.likelyIncorrectWeight());
        incorrectContribution = Math.min(incorrectContribution, policy.maxSingleEventContribution()
                * Math.max(features.directConfirmedIncorrectCount(), features.likelyIncorrectCount()));

        int mitigation = Math.min(
                policy.confirmedCorrectMitigation() * features.confirmedCorrectCount(),
                incorrectContribution);
        int rawRisk = Math.max(0, incorrectContribution - mitigation);
        rawRisk = Math.min(rawRisk, policy.maxTotalRisk());

        if (features.directConfirmedIncorrectCount() <= 1 && features.likelyIncorrectCount() == 0) {
            rawRisk = Math.min(rawRisk, policy.singleIncorrectRiskCap());
        }

        FraudAssessment outcomeAssessment = assessOutcomeInconsistency(features, policy, rawRisk);
        List<FraudAssessment> assessments = List.of(outcomeAssessment);
        Optional<FraudHardAnomalyType> hardAnomaly = hardAnomaly(features, policy);

        FraudRiskScore riskScore = FraudRiskScore.of(rawRisk);
        FraudRiskBand riskBand = riskBand(rawRisk, hardAnomaly.isPresent(), policy);
        FraudConfidenceBand confidenceBand = confidenceBand(evidenceCount, policy);
        FraudDisposition disposition = disposition(evidenceCount, riskBand, hardAnomaly.isPresent(), policy);

        return new FraudEvaluation(
                features.subject(),
                features.domain(),
                assessments,
                hardAnomaly,
                riskScore,
                riskBand,
                confidenceBand,
                features.evidenceVolume(),
                disposition,
                decisiveRule(disposition, hardAnomaly, outcomeAssessment),
                policy.policyVersion(),
                context.evaluatedAt(),
                features.windowStart(),
                features.windowEnd());
    }

    private static FraudPolicyConfig policyFor(String version) {
        if (FraudPolicyConfig.POLICY_VERSION.equals(version)) {
            return FraudPolicyConfig.referenceV1();
        }
        throw new UnsupportedFraudPolicyVersionException("Unsupported fraud policy version: " + version);
    }

    private static FraudEvaluation unsupportedDomain(
            FraudFeatureVector features,
            FraudEvaluationContext context,
            FraudPolicyConfig policy) {
        return new FraudEvaluation(
                features.subject(),
                features.domain(),
                List.of(),
                Optional.empty(),
                FraudRiskScore.zero(),
                FraudRiskBand.MINIMAL,
                FraudConfidenceBand.NONE,
                features.evidenceVolume(),
                FraudDisposition.POLICY_UNSUPPORTED,
                "POLICY_UNSUPPORTED_DOMAIN",
                policy.policyVersion(),
                context.evaluatedAt(),
                features.windowStart(),
                features.windowEnd());
    }

    private static FraudEvaluation insufficientEvidence(
            FraudFeatureVector features,
            FraudEvaluationContext context,
            FraudPolicyConfig policy,
            String rule) {
        return new FraudEvaluation(
                features.subject(),
                features.domain(),
                List.of(new FraudAssessment(
                        FraudAssessmentCategory.OUTCOME_INCONSISTENCY,
                        FraudAssessmentLevel.NONE,
                        0,
                        FraudAttributionQuality.NONE,
                        features.eligibleContributionCount(),
                        rule)),
                Optional.empty(),
                FraudRiskScore.zero(),
                FraudRiskBand.MINIMAL,
                FraudConfidenceBand.NONE,
                features.evidenceVolume(),
                FraudDisposition.INSUFFICIENT_EVIDENCE,
                rule,
                policy.policyVersion(),
                context.evaluatedAt(),
                features.windowStart(),
                features.windowEnd());
    }

    private static FraudAssessment assessOutcomeInconsistency(
            FraudFeatureVector features,
            FraudPolicyConfig policy,
            int rawRisk) {
        int incorrectEvents = features.directConfirmedIncorrectCount() + features.likelyIncorrectCount();
        FraudAssessmentLevel level = switch (incorrectEvents) {
            case 0 -> FraudAssessmentLevel.NONE;
            case 1 -> FraudAssessmentLevel.LOW;
            case 2, 3 -> FraudAssessmentLevel.MODERATE;
            default -> FraudAssessmentLevel.HIGH;
        };
        FraudAttributionQuality attribution = features.directConfirmedIncorrectCount() > 0
                ? FraudAttributionQuality.DIRECT
                : features.likelyIncorrectCount() > 0 ? FraudAttributionQuality.PARTIAL : FraudAttributionQuality.NONE;
        return new FraudAssessment(
                FraudAssessmentCategory.OUTCOME_INCONSISTENCY,
                level,
                Math.min(rawRisk, policy.maxCategoryContribution()),
                attribution,
                incorrectEvents,
                "OUTCOME_INCONSISTENCY_" + level.name());
    }

    private static Optional<FraudHardAnomalyType> hardAnomaly(FraudFeatureVector features, FraudPolicyConfig policy) {
        if (features.directConfirmedIncorrectCount() >= policy.hardAnomalyConfirmedIncorrectThreshold()) {
            return Optional.of(FraudHardAnomalyType.REPEATED_DIRECT_CONFIRMED_INCORRECT);
        }
        return Optional.empty();
    }

    private static FraudRiskBand riskBand(int rawRisk, boolean hardAnomaly, FraudPolicyConfig policy) {
        if (hardAnomaly && rawRisk >= policy.criticalRiskThreshold()) {
            return FraudRiskBand.CRITICAL;
        }
        if (rawRisk >= policy.highRiskThreshold()) {
            return FraudRiskBand.HIGH;
        }
        if (rawRisk >= policy.elevatedRiskThreshold()) {
            return FraudRiskBand.ELEVATED;
        }
        if (rawRisk > 0) {
            return FraudRiskBand.LOW;
        }
        return FraudRiskBand.MINIMAL;
    }

    private static FraudConfidenceBand confidenceBand(int evidenceCount, FraudPolicyConfig policy) {
        if (evidenceCount <= 0) {
            return FraudConfidenceBand.NONE;
        }
        if (evidenceCount >= policy.highConfidenceEvidenceThreshold()) {
            return FraudConfidenceBand.HIGH;
        }
        if (evidenceCount >= policy.mediumConfidenceEvidenceThreshold()) {
            return FraudConfidenceBand.MEDIUM;
        }
        return FraudConfidenceBand.LOW;
    }

    private static FraudDisposition disposition(
            int evidenceCount,
            FraudRiskBand riskBand,
            boolean hardAnomaly,
            FraudPolicyConfig policy) {
        if (evidenceCount < policy.minimumEvidenceVolume()) {
            return FraudDisposition.INSUFFICIENT_EVIDENCE;
        }
        if (riskBand == FraudRiskBand.MINIMAL) {
            return FraudDisposition.NO_SIGNAL;
        }
        if (evidenceCount < policy.minimumEvidenceForElevated()) {
            return FraudDisposition.OBSERVE;
        }
        if (hardAnomaly && (riskBand == FraudRiskBand.HIGH || riskBand == FraudRiskBand.CRITICAL)) {
            return FraudDisposition.REVIEW_CANDIDATE;
        }
        if (riskBand == FraudRiskBand.ELEVATED || riskBand == FraudRiskBand.HIGH || riskBand == FraudRiskBand.CRITICAL) {
            return FraudDisposition.ELEVATED_RISK;
        }
        return FraudDisposition.OBSERVE;
    }

    private static String decisiveRule(
            FraudDisposition disposition,
            Optional<FraudHardAnomalyType> hardAnomaly,
            FraudAssessment assessment) {
        if (hardAnomaly.isPresent()) {
            return "HARD_" + hardAnomaly.get().name();
        }
        return disposition.name() + "_" + assessment.decisiveReason();
    }
}
