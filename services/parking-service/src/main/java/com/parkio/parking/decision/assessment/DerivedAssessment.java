package com.parkio.parking.decision.assessment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Combined derived assessment attached to a {@link com.parkio.parking.decision.DecisionResult}.
 *
 * <p>Carries the category {@link AssessmentBundle} and optional aggregate {@link RiskAssessment}.
 * Contains no publication side effects. Replaces the former single-score
 * {@code EvidenceAssessment} as the primary decision input carrier.
 */
public final class DerivedAssessment {

    private final AssessmentBundle assessmentBundle;
    private final RiskAssessment riskAssessment;
    private final List<ReasonCode> reasonCodes;
    private final AssessmentVersion version;
    private final Instant evaluatedAt;

    private DerivedAssessment(
            AssessmentBundle assessmentBundle,
            RiskAssessment riskAssessment,
            List<ReasonCode> reasonCodes,
            AssessmentVersion version,
            Instant evaluatedAt) {
        this.assessmentBundle = assessmentBundle;
        this.riskAssessment = riskAssessment;
        this.reasonCodes = copyReasons(reasonCodes);
        this.version = Objects.requireNonNull(version, "version");
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    public static DerivedAssessment of(
            Optional<AssessmentBundle> assessmentBundle,
            Optional<RiskAssessment> riskAssessment,
            List<ReasonCode> reasonCodes,
            AssessmentVersion version,
            Instant evaluatedAt) {
        Objects.requireNonNull(assessmentBundle, "assessmentBundle");
        Objects.requireNonNull(riskAssessment, "riskAssessment");
        return new DerivedAssessment(
                assessmentBundle.orElse(null),
                riskAssessment.orElse(null),
                reasonCodes,
                version,
                evaluatedAt);
    }

    public Optional<AssessmentBundle> assessmentBundle() {
        return Optional.ofNullable(assessmentBundle);
    }

    public Optional<RiskAssessment> riskAssessment() {
        return Optional.ofNullable(riskAssessment);
    }

    public List<ReasonCode> reasonCodes() {
        return reasonCodes;
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
        if (!(o instanceof DerivedAssessment that)) {
            return false;
        }
        return Objects.equals(assessmentBundle, that.assessmentBundle)
                && Objects.equals(riskAssessment, that.riskAssessment)
                && reasonCodes.equals(that.reasonCodes)
                && version.equals(that.version)
                && evaluatedAt.equals(that.evaluatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assessmentBundle, riskAssessment, reasonCodes, version, evaluatedAt);
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
}
