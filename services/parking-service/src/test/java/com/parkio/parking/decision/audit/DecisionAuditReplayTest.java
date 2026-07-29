package com.parkio.parking.decision.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.calibration.DecisionCalibrationObservationFactory;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.policy.DecisionEngine;
import com.parkio.parking.decision.policy.DecisionGoldenFixtures;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowDecisionComparison;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DecisionAuditReplayTest {

    private final DecisionEngine engine = new DecisionEngine();

    @Test
    void identicalPolicyReplayReproducesDecisionResult() {
        DecisionAuditRecord record = audit(DecisionGoldenFixtures.strongNormal());
        DecisionReplayComparison comparison = DecisionAuditReplayer.replayAndCompare(record);
        assertThat(comparison.identical()).isTrue();
        assertThat(comparison.dispositionUnchanged()).isTrue();
        assertThat(comparison.decisiveRuleUnchanged()).isTrue();
    }

    @Test
    void mediaMismatchFixtureReplaysIdentically() {
        DecisionAuditRecord record = audit(DecisionGoldenFixtures.mediaMismatch());
        assertThat(DecisionAuditReplayer.replayAndCompare(record).identical()).isTrue();
    }

    @Test
    void unknownPolicyVersionFailsExplicitly() {
        DecisionAuditRecord record = audit(DecisionGoldenFixtures.strongNormal());
        DecisionAuditRecord mutated = DecisionAuditRecord.of(
                record.auditId(),
                record.parkingSpotId(),
                record.evaluationId(),
                "decision-shadow-v2",
                record.decisionEngineVersion(),
                record.shadowModeVersion(),
                record.evaluatedAt(),
                record.evidence(),
                EvaluationContext.of(
                        com.parkio.parking.decision.assessment.AssessmentVersion.of("decision-shadow-v2"),
                        record.evaluatedAt(),
                        "golden"),
                DecisionResult.of(
                        record.decision().parkingSpotId(),
                        record.decision().evaluationId(),
                        record.decision().disposition(),
                        record.decision().assessment(),
                        record.decision().reasonCodes(),
                        record.decision().decisiveRule(),
                        "decision-shadow-v2",
                        record.decision().decidedAt(),
                        record.decision().asynchronousFollowUpRequired()),
                record.legacyOutcome(),
                record.comparisonCategory(),
                record.riskBand(),
                record.hardConstraintFamily(),
                record.evidenceProfile(),
                record.decisiveRule(),
                record.createdAt());

        assertThatThrownBy(() -> DecisionAuditReplayer.replay(mutated))
                .isInstanceOf(UnsupportedDecisionVersionException.class)
                .hasMessageContaining("decision-shadow-v2");
    }

    @Test
    void unknownEngineVersionFailsExplicitly() {
        assertThatThrownBy(() -> DecisionEngineFactory.forVersions("decision-shadow-v1", "decision-engine-v9"))
                .isInstanceOf(UnsupportedDecisionVersionException.class);
    }

    private DecisionAuditRecord audit(EvidenceVector evidence) {
        EvaluationContext context = DecisionGoldenFixtures.context();
        DecisionResult decision = engine.evaluate(evidence, context);
        LegacyPublicationOutcome legacy = new LegacyPublicationOutcome(
                ParkingSpotStatus.PENDING_VALIDATION, ParkingSpotStatus.ACTIVE, LegacyPublicationOutcome.Kind.APPLIED);
        ShadowDecisionComparison comparison = ShadowDecisionComparison.of(legacy, decision);
        var observation = DecisionCalibrationObservationFactory.from(
                evidence, decision, comparison, Duration.ofMillis(5), DecisionGoldenFixtures.T0);
        return DecisionAuditRecordFactory.fromSuccessfulShadow(
                UUID.randomUUID(), evidence, context, decision, comparison, observation, DecisionGoldenFixtures.T0);
    }
}