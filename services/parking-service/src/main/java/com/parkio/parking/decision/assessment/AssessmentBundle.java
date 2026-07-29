package com.parkio.parking.decision.assessment;

import com.parkio.parking.decision.score.EvidenceScore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable aggregate of domain assessments for one evaluation.
 *
 * <p>Presence of a category means it was evaluated. Absence means the category was
 * not evaluated (capability deferred or not applicable to the pipeline stage) —
 * distinct from {@link AssessmentLevel#INSUFFICIENT_EVIDENCE} and
 * {@link AssessmentLevel#NOT_APPLICABLE}.
 *
 * <p>Does not select publication disposition or mutate ParkingSpot state.
 */
public final class AssessmentBundle {

    private static final Comparator<DomainAssessment> CATEGORY_ORDER =
            Comparator.comparing(assessment -> assessment.category().ordinal());

    private final UUID parkingSpotId;
    private final UUID evaluationId;
    private final String evidenceSchemaVersion;
    private final List<DomainAssessment> assessments;
    private final AssessmentVersion evaluationPolicyVersion;
    private final Instant evaluatedAt;
    private final List<ReasonCode> globalReasonCodes;
    private final EvidenceScore aggregateEvidenceScore;

    private AssessmentBundle(
            UUID parkingSpotId,
            UUID evaluationId,
            String evidenceSchemaVersion,
            List<DomainAssessment> assessments,
            AssessmentVersion evaluationPolicyVersion,
            Instant evaluatedAt,
            List<ReasonCode> globalReasonCodes,
            EvidenceScore aggregateEvidenceScore) {
        this.parkingSpotId = Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        this.evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        this.evidenceSchemaVersion = requireNonBlank(evidenceSchemaVersion, "evidenceSchemaVersion");
        this.evaluationPolicyVersion =
                Objects.requireNonNull(evaluationPolicyVersion, "evaluationPolicyVersion");
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        this.globalReasonCodes = copyReasons(globalReasonCodes);
        this.aggregateEvidenceScore = aggregateEvidenceScore;
        this.assessments = canonicalize(assessments);
    }

    public static AssessmentBundle of(
            UUID parkingSpotId,
            UUID evaluationId,
            String evidenceSchemaVersion,
            List<DomainAssessment> assessments,
            AssessmentVersion evaluationPolicyVersion,
            Instant evaluatedAt,
            List<ReasonCode> globalReasonCodes) {
        return new AssessmentBundle(
                parkingSpotId,
                evaluationId,
                evidenceSchemaVersion,
                assessments,
                evaluationPolicyVersion,
                evaluatedAt,
                globalReasonCodes,
                null);
    }

    public static AssessmentBundle of(
            UUID parkingSpotId,
            UUID evaluationId,
            String evidenceSchemaVersion,
            List<DomainAssessment> assessments,
            AssessmentVersion evaluationPolicyVersion,
            Instant evaluatedAt,
            List<ReasonCode> globalReasonCodes,
            Optional<EvidenceScore> aggregateEvidenceScore) {
        Objects.requireNonNull(aggregateEvidenceScore, "aggregateEvidenceScore");
        return new AssessmentBundle(
                parkingSpotId,
                evaluationId,
                evidenceSchemaVersion,
                assessments,
                evaluationPolicyVersion,
                evaluatedAt,
                globalReasonCodes,
                aggregateEvidenceScore.orElse(null));
    }

    public UUID parkingSpotId() {
        return parkingSpotId;
    }

    public UUID evaluationId() {
        return evaluationId;
    }

    public String evidenceSchemaVersion() {
        return evidenceSchemaVersion;
    }

    public List<DomainAssessment> assessments() {
        return assessments;
    }

    public AssessmentVersion evaluationPolicyVersion() {
        return evaluationPolicyVersion;
    }

    public Instant evaluatedAt() {
        return evaluatedAt;
    }

    public List<ReasonCode> globalReasonCodes() {
        return globalReasonCodes;
    }

    public Optional<EvidenceScore> aggregateEvidenceScore() {
        return Optional.ofNullable(aggregateEvidenceScore);
    }

    public Optional<DomainAssessment> find(AssessmentCategory category) {
        Objects.requireNonNull(category, "category");
        for (DomainAssessment assessment : assessments) {
            if (assessment.category() == category) {
                return Optional.of(assessment);
            }
        }
        return Optional.empty();
    }

    public boolean hasCategory(AssessmentCategory category) {
        return find(category).isPresent();
    }

    public boolean hasHardConstraint() {
        for (DomainAssessment assessment : assessments) {
            if (assessment.hardConstraint()) {
                return true;
            }
        }
        return false;
    }

    public EnumSet<AssessmentCategory> evaluatedCategories() {
        EnumSet<AssessmentCategory> set = EnumSet.noneOf(AssessmentCategory.class);
        for (DomainAssessment assessment : assessments) {
            set.add(assessment.category());
        }
        return set;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AssessmentBundle that)) {
            return false;
        }
        return parkingSpotId.equals(that.parkingSpotId)
                && evaluationId.equals(that.evaluationId)
                && evidenceSchemaVersion.equals(that.evidenceSchemaVersion)
                && assessments.equals(that.assessments)
                && evaluationPolicyVersion.equals(that.evaluationPolicyVersion)
                && evaluatedAt.equals(that.evaluatedAt)
                && globalReasonCodes.equals(that.globalReasonCodes)
                && Objects.equals(aggregateEvidenceScore, that.aggregateEvidenceScore);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                parkingSpotId,
                evaluationId,
                evidenceSchemaVersion,
                assessments,
                evaluationPolicyVersion,
                evaluatedAt,
                globalReasonCodes,
                aggregateEvidenceScore);
    }

    private static List<DomainAssessment> canonicalize(List<DomainAssessment> assessments) {
        Objects.requireNonNull(assessments, "assessments");
        Map<AssessmentCategory, DomainAssessment> byCategory = new EnumMap<>(AssessmentCategory.class);
        for (DomainAssessment assessment : assessments) {
            if (assessment == null) {
                throw new IllegalArgumentException("assessments must not contain null");
            }
            if (byCategory.containsKey(assessment.category())) {
                throw new IllegalArgumentException(
                        "duplicate assessment category: " + assessment.category());
            }
            byCategory.put(assessment.category(), assessment);
        }
        List<DomainAssessment> ordered = new ArrayList<>(byCategory.values());
        ordered.sort(CATEGORY_ORDER);
        return Collections.unmodifiableList(ordered);
    }

    private static List<ReasonCode> copyReasons(List<ReasonCode> reasonCodes) {
        Objects.requireNonNull(reasonCodes, "globalReasonCodes");
        List<ReasonCode> copy = new ArrayList<>(reasonCodes.size());
        for (ReasonCode code : reasonCodes) {
            if (code == null) {
                throw new IllegalArgumentException("globalReasonCodes must not contain null");
            }
            copy.add(code);
        }
        return Collections.unmodifiableList(copy);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}