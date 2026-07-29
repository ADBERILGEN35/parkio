package com.parkio.parking.decision.assessment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable domain interpretation of evidence for one {@link AssessmentCategory}.
 *
 * <p>Does not embed provider payloads, select {@code PublicationDisposition}, or
 * compute scores. Optional numeric fields are category intensity / confidence only —
 * not {@code EvidenceScore} or {@code RiskScore}.
 */
public final class DomainAssessment {

    private final AssessmentCategory category;
    private final AssessmentLevel level;
    private final Integer categoryScore;
    private final Integer confidence;
    private final AssessmentCompleteness completeness;
    private final boolean hardConstraint;
    private final List<ReasonCode> reasonCodes;
    private final List<EvidenceReference> evidenceReferences;
    private final AssessmentVersion version;
    private final Instant evaluatedAt;

    private DomainAssessment(
            AssessmentCategory category,
            AssessmentLevel level,
            Integer categoryScore,
            Integer confidence,
            AssessmentCompleteness completeness,
            boolean hardConstraint,
            List<ReasonCode> reasonCodes,
            List<EvidenceReference> evidenceReferences,
            AssessmentVersion version,
            Instant evaluatedAt) {
        this.category = Objects.requireNonNull(category, "category");
        this.level = Objects.requireNonNull(level, "level");
        this.categoryScore = requireOptionalScore(categoryScore, "categoryScore");
        this.confidence = requireOptionalScore(confidence, "confidence");
        this.completeness = Objects.requireNonNull(completeness, "completeness");
        this.hardConstraint = hardConstraint;
        this.reasonCodes = copyReasons(reasonCodes);
        this.evidenceReferences = copyReferences(evidenceReferences);
        this.version = Objects.requireNonNull(version, "version");
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (hardConstraint && level != AssessmentLevel.CRITICAL) {
            throw new IllegalArgumentException(
                    "hardConstraint requires AssessmentLevel.CRITICAL, was " + level);
        }
        if (level == AssessmentLevel.NOT_APPLICABLE && !evidenceReferences.isEmpty()) {
            throw new IllegalArgumentException(
                    "NOT_APPLICABLE assessments must not reference evidence items");
        }
    }

    public static DomainAssessment of(
            AssessmentCategory category,
            AssessmentLevel level,
            AssessmentCompleteness completeness,
            boolean hardConstraint,
            List<ReasonCode> reasonCodes,
            List<EvidenceReference> evidenceReferences,
            AssessmentVersion version,
            Instant evaluatedAt) {
        return new DomainAssessment(
                category,
                level,
                null,
                null,
                completeness,
                hardConstraint,
                reasonCodes,
                evidenceReferences,
                version,
                evaluatedAt);
    }

    public static DomainAssessment of(
            AssessmentCategory category,
            AssessmentLevel level,
            OptionalInt categoryScore,
            OptionalInt confidence,
            AssessmentCompleteness completeness,
            boolean hardConstraint,
            List<ReasonCode> reasonCodes,
            List<EvidenceReference> evidenceReferences,
            AssessmentVersion version,
            Instant evaluatedAt) {
        Objects.requireNonNull(categoryScore, "categoryScore");
        Objects.requireNonNull(confidence, "confidence");
        return new DomainAssessment(
                category,
                level,
                categoryScore.isPresent() ? categoryScore.getAsInt() : null,
                confidence.isPresent() ? confidence.getAsInt() : null,
                completeness,
                hardConstraint,
                reasonCodes,
                evidenceReferences,
                version,
                evaluatedAt);
    }

    public AssessmentCategory category() {
        return category;
    }

    public AssessmentLevel level() {
        return level;
    }

    public OptionalInt categoryScore() {
        return categoryScore == null ? OptionalInt.empty() : OptionalInt.of(categoryScore);
    }

    public OptionalInt confidence() {
        return confidence == null ? OptionalInt.empty() : OptionalInt.of(confidence);
    }

    public AssessmentCompleteness completeness() {
        return completeness;
    }

    public boolean hardConstraint() {
        return hardConstraint;
    }

    public List<ReasonCode> reasonCodes() {
        return reasonCodes;
    }

    public List<EvidenceReference> evidenceReferences() {
        return evidenceReferences;
    }

    public AssessmentVersion version() {
        return version;
    }

    public Instant evaluatedAt() {
        return evaluatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DomainAssessment that)) {
            return false;
        }
        return hardConstraint == that.hardConstraint
                && category == that.category
                && level == that.level
                && Objects.equals(categoryScore, that.categoryScore)
                && Objects.equals(confidence, that.confidence)
                && completeness == that.completeness
                && reasonCodes.equals(that.reasonCodes)
                && evidenceReferences.equals(that.evidenceReferences)
                && version.equals(that.version)
                && evaluatedAt.equals(that.evaluatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                category,
                level,
                categoryScore,
                confidence,
                completeness,
                hardConstraint,
                reasonCodes,
                evidenceReferences,
                version,
                evaluatedAt);
    }

    private static Integer requireOptionalScore(Integer score, String field) {
        if (score == null) {
            return null;
        }
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException(field + " must be between 0 and 100 inclusive");
        }
        return score;
    }

    private static List<ReasonCode> copyReasons(List<ReasonCode> reasonCodes) {
        Objects.requireNonNull(reasonCodes, "reasonCodes");
        List<ReasonCode> copy = new ArrayList<>(reasonCodes.size());
        for (ReasonCode code : reasonCodes) {
            if (code == null) {
                throw new IllegalArgumentException("reasonCodes must not contain null");
            }
            copy.add(code);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<EvidenceReference> copyReferences(List<EvidenceReference> refs) {
        Objects.requireNonNull(refs, "evidenceReferences");
        LinkedHashSet<EvidenceReference> unique = new LinkedHashSet<>();
        for (EvidenceReference ref : refs) {
            if (ref == null) {
                throw new IllegalArgumentException("evidenceReferences must not contain null");
            }
            unique.add(ref);
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }
}