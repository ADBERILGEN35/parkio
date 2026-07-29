package com.parkio.parking.decision.evaluation;

import com.parkio.parking.decision.assessment.AssessmentVersion;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable inputs required to evaluate an EvidenceVector into domain assessments.
 * Contains no repositories, clocks, or infrastructure services.
 */
public final class EvaluationContext {

    private final AssessmentVersion evaluationPolicyVersion;
    private final Instant evaluatedAt;
    private final String scenarioKey;

    private EvaluationContext(
            AssessmentVersion evaluationPolicyVersion, Instant evaluatedAt, String scenarioKey) {
        this.evaluationPolicyVersion =
                Objects.requireNonNull(evaluationPolicyVersion, "evaluationPolicyVersion");
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        this.scenarioKey = normalizeOptional(scenarioKey);
    }

    public static EvaluationContext of(AssessmentVersion evaluationPolicyVersion, Instant evaluatedAt) {
        return new EvaluationContext(evaluationPolicyVersion, evaluatedAt, null);
    }

    public static EvaluationContext of(
            AssessmentVersion evaluationPolicyVersion, Instant evaluatedAt, String scenarioKey) {
        return new EvaluationContext(evaluationPolicyVersion, evaluatedAt, scenarioKey);
    }

    public AssessmentVersion evaluationPolicyVersion() {
        return evaluationPolicyVersion;
    }

    public Instant evaluatedAt() {
        return evaluatedAt;
    }

    public Optional<String> scenarioKey() {
        return Optional.ofNullable(scenarioKey);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvaluationContext that)) {
            return false;
        }
        return evaluationPolicyVersion.equals(that.evaluationPolicyVersion)
                && evaluatedAt.equals(that.evaluatedAt)
                && Objects.equals(scenarioKey, that.scenarioKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(evaluationPolicyVersion, evaluatedAt, scenarioKey);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("scenarioKey must be at most 64 characters");
        }
        return trimmed;
    }
}