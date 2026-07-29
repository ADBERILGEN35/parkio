package com.parkio.parking.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.assessment.AssessmentVersion;
import com.parkio.parking.decision.assessment.DerivedAssessment;
import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.calibration.DecisivePolicyRule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DecisionResultTest {

    private static final Instant NOW = Instant.parse("2026-07-27T15:00:00Z");

    private static DerivedAssessment assessment() {
        return DerivedAssessment.of(
                Optional.empty(),
                Optional.empty(),
                List.of(ReasonCode.of("POLICY_HOLD")),
                AssessmentVersion.of("assessment-v1"),
                NOW);
    }

    @Test
    void requiresDispositionPolicyVersionAndReasonCodes() {
        DecisionResult result = DecisionResult.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                PublicationDisposition.HOLD,
                assessment(),
                List.of(ReasonCode.of("AWAITING_AI")),
                DecisivePolicyRule.FALLBACK_HOLD,
                "decision-policy-v1",
                NOW,
                true);

        assertThat(result.disposition()).isEqualTo(PublicationDisposition.HOLD);
        assertThat(result.policyVersion()).isEqualTo("decision-policy-v1");
        assertThat(result.decisiveRule()).isEqualTo(DecisivePolicyRule.FALLBACK_HOLD);
        assertThat(result.reasonCodes()).containsExactly(ReasonCode.of("AWAITING_AI"));
        assertThat(result.asynchronousFollowUpRequired()).isTrue();
    }

    @Test
    void rejectsEmptyReasonsOrBlankPolicy() {
        assertThatThrownBy(() -> DecisionResult.of(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PublicationDisposition.REJECTED,
                        assessment(),
                        List.of(),
                        DecisivePolicyRule.CRITICAL_NOT_PARKING,
                        "decision-policy-v1",
                        NOW,
                        false))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> DecisionResult.of(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PublicationDisposition.REJECTED,
                        assessment(),
                        List.of(ReasonCode.of("AI_REJECTED")),
                        DecisivePolicyRule.CRITICAL_NOT_PARKING,
                        "  ",
                        NOW,
                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicationDispositionContainsAllSixTargetValues() {
        assertThat(PublicationDisposition.values())
                .containsExactlyInAnyOrder(
                        PublicationDisposition.FULL_PUBLISH,
                        PublicationDisposition.LIMITED_PUBLISH,
                        PublicationDisposition.HOLD,
                        PublicationDisposition.SHADOW,
                        PublicationDisposition.EXPIRED,
                        PublicationDisposition.REJECTED);
    }
}