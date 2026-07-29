package com.parkio.parking.decision.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.evidence.EvidenceVector;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssessmentBundleTest {

    private static final Instant NOW = Instant.parse("2026-07-27T18:00:00Z");
    private static final AssessmentVersion VERSION = AssessmentVersion.of("eval-policy-v0");

    @Test
    void ordersAssessmentsDeterministicallyAndRejectsDuplicates() {
        UUID spotId = UUID.randomUUID();
        UUID evaluationId = UUID.randomUUID();
        DomainAssessment legality = DomainAssessment.of(
                AssessmentCategory.LEGALITY,
                AssessmentLevel.CONCERNING,
                AssessmentCompleteness.PARTIAL,
                false,
                List.of(ReasonCode.of("LEGAL_RISK")),
                List.of(),
                VERSION,
                NOW);
        DomainAssessment content = DomainAssessment.of(
                AssessmentCategory.CONTENT,
                AssessmentLevel.POSITIVE,
                AssessmentCompleteness.PARTIAL,
                false,
                List.of(ReasonCode.of("CONTENT_OK")),
                List.of(),
                VERSION,
                NOW);

        AssessmentBundle bundle = AssessmentBundle.of(
                spotId,
                evaluationId,
                EvidenceVector.SCHEMA_VERSION_V1,
                List.of(legality, content),
                VERSION,
                NOW,
                List.of());

        assertThat(bundle.assessments())
                .extracting(DomainAssessment::category)
                .containsExactly(AssessmentCategory.CONTENT, AssessmentCategory.LEGALITY);
        assertThat(bundle.find(AssessmentCategory.CONTENT)).isPresent();
        assertThat(bundle.hasCategory(AssessmentCategory.TRUST)).isFalse();
        assertThat(bundle.hasCategory(AssessmentCategory.LOCATION)).isFalse();

        AssessmentBundle again = AssessmentBundle.of(
                spotId,
                evaluationId,
                EvidenceVector.SCHEMA_VERSION_V1,
                List.of(legality, content),
                VERSION,
                NOW,
                List.of());
        assertThat(bundle).isEqualTo(again);

        assertThatThrownBy(() -> AssessmentBundle.of(
                        spotId,
                        evaluationId,
                        EvidenceVector.SCHEMA_VERSION_V1,
                        List.of(content, content),
                        VERSION,
                        NOW,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void missingCategoryIsDistinctFromInsufficientEvidence() {
        DomainAssessment insufficient = DomainAssessment.of(
                AssessmentCategory.CONTENT,
                AssessmentLevel.INSUFFICIENT_EVIDENCE,
                AssessmentCompleteness.EMPTY,
                false,
                List.of(ReasonCode.of("NO_IMAGE_QUALITY")),
                List.of(),
                VERSION,
                NOW);
        AssessmentBundle bundle = AssessmentBundle.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                EvidenceVector.SCHEMA_VERSION_V1,
                List.of(insufficient),
                VERSION,
                NOW,
                List.of());

        assertThat(bundle.find(AssessmentCategory.CONTENT))
                .get()
                .extracting(DomainAssessment::level)
                .isEqualTo(AssessmentLevel.INSUFFICIENT_EVIDENCE);
        assertThat(bundle.find(AssessmentCategory.TRUST)).isEmpty();
    }

    @Test
    void hardConstraintFlagPropagatesFromCategoryAssessments() {
        DomainAssessment integrity = DomainAssessment.of(
                AssessmentCategory.INTEGRITY,
                AssessmentLevel.CRITICAL,
                AssessmentCompleteness.COMPLETE,
                true,
                List.of(ReasonCode.of("MEDIA_SPOT_MISMATCH")),
                List.of(),
                VERSION,
                NOW);
        AssessmentBundle bundle = AssessmentBundle.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                EvidenceVector.SCHEMA_VERSION_V1,
                List.of(integrity),
                VERSION,
                NOW,
                List.of(ReasonCode.of("HARD_CONSTRAINT")));

        assertThat(bundle.hasHardConstraint()).isTrue();
        assertThat(bundle.globalReasonCodes()).containsExactly(ReasonCode.of("HARD_CONSTRAINT"));
        assertThat(bundle.aggregateEvidenceScore()).isEqualTo(Optional.empty());
    }

    @Test
    void doesNotProducePublicationDisposition() {
        AssessmentBundle bundle = AssessmentBundle.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                EvidenceVector.SCHEMA_VERSION_V1,
                List.of(),
                VERSION,
                NOW,
                List.of());
        assertThat(bundle.assessments()).isEmpty();
        assertThat(PublicationDisposition.values()).isNotEmpty();
    }
}