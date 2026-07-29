package com.parkio.parking.decision.assessment;

import com.parkio.parking.decision.score.RiskScore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Derived risk assessment for one evaluation.
 *
 * <p>Risk answers: how risky would it be to expose this ParkingSpot under the
 * evaluated policy and evidence snapshot? Score absence means uncomputed, not
 * zero risk. Hard constraints remain visible even when score is present.
 */
public final class RiskAssessment {

    private final RiskScore score;
    private final List<ReasonCode> reasonCodes;
    private final AssessmentVersion version;
    private final Instant evaluatedAt;
    private final boolean hardConstraintActive;
    private final List<EvidenceReference> contributingEvidence;

    private RiskAssessment(
            RiskScore score,
            List<ReasonCode> reasonCodes,
            AssessmentVersion version,
            Instant evaluatedAt,
            boolean hardConstraintActive,
            List<EvidenceReference> contributingEvidence) {
        this.score = score;
        this.reasonCodes = copyReasons(reasonCodes);
        this.version = Objects.requireNonNull(version, "version");
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        this.hardConstraintActive = hardConstraintActive;
        this.contributingEvidence = copyReferences(contributingEvidence);
    }

    public static RiskAssessment of(
            Optional<RiskScore> score,
            List<ReasonCode> reasonCodes,
            AssessmentVersion version,
            Instant evaluatedAt) {
        return of(score, reasonCodes, version, evaluatedAt, false, List.of());
    }

    public static RiskAssessment of(
            Optional<RiskScore> score,
            List<ReasonCode> reasonCodes,
            AssessmentVersion version,
            Instant evaluatedAt,
            boolean hardConstraintActive,
            List<EvidenceReference> contributingEvidence) {
        Objects.requireNonNull(score, "score");
        return new RiskAssessment(
                score.orElse(null),
                reasonCodes,
                version,
                evaluatedAt,
                hardConstraintActive,
                contributingEvidence);
    }

    public Optional<RiskScore> score() {
        return Optional.ofNullable(score);
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

    public boolean hardConstraintActive() {
        return hardConstraintActive;
    }

    public List<EvidenceReference> contributingEvidence() {
        return contributingEvidence;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RiskAssessment that)) {
            return false;
        }
        return hardConstraintActive == that.hardConstraintActive
                && Objects.equals(score, that.score)
                && reasonCodes.equals(that.reasonCodes)
                && version.equals(that.version)
                && evaluatedAt.equals(that.evaluatedAt)
                && contributingEvidence.equals(that.contributingEvidence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                score, reasonCodes, version, evaluatedAt, hardConstraintActive, contributingEvidence);
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
        Objects.requireNonNull(refs, "contributingEvidence");
        LinkedHashSet<EvidenceReference> unique = new LinkedHashSet<>();
        for (EvidenceReference ref : refs) {
            if (ref == null) {
                throw new IllegalArgumentException("contributingEvidence must not contain null");
            }
            unique.add(ref);
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }
}
