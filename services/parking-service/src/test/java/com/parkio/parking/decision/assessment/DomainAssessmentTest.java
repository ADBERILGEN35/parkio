package com.parkio.parking.decision.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidencePolarity;
import com.parkio.parking.decision.evidence.EvidenceSource;
import com.parkio.parking.decision.evidence.EvidenceType;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class DomainAssessmentTest {

    private static final Instant NOW = Instant.parse("2026-07-27T18:00:00Z");
    private static final AssessmentVersion VERSION = AssessmentVersion.of("eval-policy-v0");

    @Test
    void constructsValidAssessmentWithEvidenceReferences() {
        EvidenceItem item = EvidenceItem.of(
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                EvidencePolarity.SUPPORTS_PUBLISH,
                55,
                NOW,
                ReasonCode.of("AI_STATUS_PASSED"),
                "event-1");
        EvidenceReference ref = EvidenceReference.from(item);

        DomainAssessment assessment = DomainAssessment.of(
                AssessmentCategory.CONTENT,
                AssessmentLevel.POSITIVE,
                OptionalInt.of(70),
                OptionalInt.of(80),
                AssessmentCompleteness.PARTIAL,
                false,
                List.of(ReasonCode.of("CONTENT_OK")),
                List.of(ref),
                VERSION,
                NOW);

        assertThat(assessment.category()).isEqualTo(AssessmentCategory.CONTENT);
        assertThat(assessment.level()).isEqualTo(AssessmentLevel.POSITIVE);
        assertThat(assessment.categoryScore()).hasValue(70);
        assertThat(assessment.confidence()).hasValue(80);
        assertThat(assessment.evidenceReferences()).containsExactly(ref);
        assertThat(assessment.hardConstraint()).isFalse();
    }

    @Test
    void hardConstraintRequiresCriticalLevel() {
        assertThatThrownBy(() -> DomainAssessment.of(
                        AssessmentCategory.INTEGRITY,
                        AssessmentLevel.CONCERNING,
                        AssessmentCompleteness.COMPLETE,
                        true,
                        List.of(ReasonCode.of("MEDIA_SPOT_MISMATCH")),
                        List.of(),
                        VERSION,
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CRITICAL");
    }

    @Test
    void notApplicableMustNotReferenceEvidence() {
        EvidenceReference ref = EvidenceReference.of(
                "k",
                EvidenceType.USER_TRUST_HISTORY,
                EvidenceSource.GAMIFICATION_SERVICE,
                ReasonCode.of("TRUST_SIGNAL"));

        assertThatThrownBy(() -> DomainAssessment.of(
                        AssessmentCategory.TRUST,
                        AssessmentLevel.NOT_APPLICABLE,
                        AssessmentCompleteness.EMPTY,
                        false,
                        List.of(),
                        List.of(ref),
                        VERSION,
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_APPLICABLE");
    }

    @Test
    void rejectsNullCategoryAndOutOfRangeScores() {
        assertThatThrownBy(() -> DomainAssessment.of(
                        null,
                        AssessmentLevel.POSITIVE,
                        AssessmentCompleteness.EMPTY,
                        false,
                        List.of(),
                        List.of(),
                        VERSION,
                        NOW))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> DomainAssessment.of(
                        AssessmentCategory.CONTENT,
                        AssessmentLevel.POSITIVE,
                        OptionalInt.of(101),
                        OptionalInt.empty(),
                        AssessmentCompleteness.PARTIAL,
                        false,
                        List.of(),
                        List.of(),
                        VERSION,
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categoryScore");
    }

    @Test
    void dedupesEvidenceReferencesByCanonicalKey() {
        EvidenceReference a = EvidenceReference.of(
                "same-key",
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                ReasonCode.of("A"));
        EvidenceReference b = EvidenceReference.of(
                "same-key",
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                ReasonCode.of("B"));

        DomainAssessment assessment = DomainAssessment.of(
                AssessmentCategory.CONTENT,
                AssessmentLevel.UNCERTAIN,
                AssessmentCompleteness.PARTIAL,
                false,
                List.of(ReasonCode.of("CONFLICT")),
                List.of(a, b),
                VERSION,
                NOW);

        assertThat(assessment.evidenceReferences()).hasSize(1);
    }

    @Test
    void distinguishesInsufficientFromNotApplicable() {
        DomainAssessment insufficient = DomainAssessment.of(
                AssessmentCategory.CONTENT,
                AssessmentLevel.INSUFFICIENT_EVIDENCE,
                AssessmentCompleteness.EMPTY,
                false,
                List.of(ReasonCode.of("IMAGE_QUALITY_MISSING")),
                List.of(),
                VERSION,
                NOW);
        DomainAssessment notApplicable = DomainAssessment.of(
                AssessmentCategory.TRUST,
                AssessmentLevel.NOT_APPLICABLE,
                AssessmentCompleteness.EMPTY,
                false,
                List.of(ReasonCode.of("TRUST_NOT_IN_SCOPE")),
                List.of(),
                VERSION,
                NOW);

        assertThat(insufficient.level()).isNotEqualTo(notApplicable.level());
        assertThat(insufficient.level()).isEqualTo(AssessmentLevel.INSUFFICIENT_EVIDENCE);
        assertThat(notApplicable.level()).isEqualTo(AssessmentLevel.NOT_APPLICABLE);
    }
}