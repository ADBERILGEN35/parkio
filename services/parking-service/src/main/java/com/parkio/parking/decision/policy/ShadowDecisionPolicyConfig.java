package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.AssessmentLevel;
import com.parkio.parking.decision.assessment.AssessmentVersion;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable v1 reference/shadow Decision Engine configuration.
 *
 * <p>Thresholds are conservative engineering baselines for non-authoritative
 * shadow mode ({@code decision-shadow-v1}). They are <strong>not</strong>
 * product-calibrated. Integer arithmetic only (no binary floating point).
 */
public final class ShadowDecisionPolicyConfig {

    public static final AssessmentVersion POLICY_VERSION = AssessmentVersion.of("decision-shadow-v1");

    /** Full publish only when risk is at or below this value. */
    public static final int RISK_FULL_PUBLISH_MAX = 25;
    /** Above this (and not hard-constrained) prefers HOLD over FULL. */
    public static final int RISK_ELEVATED_MIN = 26;
    /** High non-final risk band upper bound before critical/final rules. */
    public static final int RISK_HIGH_MIN = 71;

    /** Empty-space strength below this with PASSED still treated as weak content. */
    public static final int EMPTY_SPACE_STRONG_MIN = 70;
    /** Image quality below this contributes to insufficient content. */
    public static final int IMAGE_QUALITY_WEAK_MAX = 40;
    /** Legal risk score at or above this is concerning. */
    public static final int LEGAL_RISK_CONCERNING_MIN = 40;
    /** Legal risk score at or above this is critical. */
    public static final int LEGAL_RISK_CRITICAL_MIN = 80;

    private static final ShadowDecisionPolicyConfig INSTANCE = new ShadowDecisionPolicyConfig();

    private final Map<AssessmentCategory, Integer> categoryWeights;
    private final Map<AssessmentLevel, Integer> levelRiskContribution;

    private ShadowDecisionPolicyConfig() {
        EnumMap<AssessmentCategory, Integer> weights = new EnumMap<>(AssessmentCategory.class);
        weights.put(AssessmentCategory.CONTENT, 30);
        weights.put(AssessmentCategory.LEGALITY, 35);
        weights.put(AssessmentCategory.LOCATION, 15);
        weights.put(AssessmentCategory.INTEGRITY, 20);
        this.categoryWeights = Map.copyOf(weights);

        EnumMap<AssessmentLevel, Integer> levels = new EnumMap<>(AssessmentLevel.class);
        levels.put(AssessmentLevel.POSITIVE, 0);
        levels.put(AssessmentLevel.ACCEPTABLE, 15);
        levels.put(AssessmentLevel.UNCERTAIN, 40);
        levels.put(AssessmentLevel.CONCERNING, 65);
        levels.put(AssessmentLevel.CRITICAL, 100);
        levels.put(AssessmentLevel.INSUFFICIENT_EVIDENCE, 50);
        levels.put(AssessmentLevel.NOT_APPLICABLE, 0);
        this.levelRiskContribution = Map.copyOf(levels);
    }

    public static ShadowDecisionPolicyConfig referenceV1() {
        return INSTANCE;
    }

    public AssessmentVersion policyVersion() {
        return POLICY_VERSION;
    }

    public int weight(AssessmentCategory category) {
        Objects.requireNonNull(category, "category");
        return categoryWeights.getOrDefault(category, 0);
    }

    public int levelRisk(AssessmentLevel level) {
        Objects.requireNonNull(level, "level");
        return levelRiskContribution.getOrDefault(level, 0);
    }

    /** Half-up integer division of {@code numerator / denominator}. */
    public static int divideHalfUp(int numerator, int denominator) {
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        if (numerator >= 0) {
            return (numerator + denominator / 2) / denominator;
        }
        return -((-numerator + denominator / 2) / denominator);
    }

    public static int clampRisk(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 100) {
            return 100;
        }
        return value;
    }
}