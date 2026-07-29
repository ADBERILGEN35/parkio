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

class DecisionAuditRecordTest {

    private final DecisionEngine engine = new DecisionEngine();

    @Test
    void buildsImmutableRecordFromSuccessfulShadow() {
        EvidenceVector evidence = DecisionGoldenFixtures.strongNormal();
        EvaluationContext context = DecisionGoldenFixtures.context();
        DecisionResult decision = engine.evaluate(evidence, context);
        LegacyPublicationOutcome legacy = new LegacyPublicationOutcome(
                ParkingSpotStatus.PENDING_VALIDATION, ParkingSpotStatus.ACTIVE, LegacyPublicationOutcome.Kind.APPLIED);
        ShadowDecisionComparison comparison = ShadowDecisionComparison.of(legacy, decision);
        var observation = DecisionCalibrationObservationFactory.from(
                evidence, decision, comparison, Duration.ofMillis(12), DecisionGoldenFixtures.T0);

        UUID auditId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        DecisionAuditRecord record = DecisionAuditRecordFactory.fromSuccessfulShadow(
                auditId, evidence, context, decision, comparison, observation, DecisionGoldenFixtures.T0);

        assertThat(record.auditId()).isEqualTo(auditId);
        assertThat(record.policyVersion()).isEqualTo("decision-shadow-v1");
        assertThat(record.decisionEngineVersion()).isEqualTo(DecisionEngineVersion.V1);
        assertThat(record.shadowModeVersion()).isEqualTo(ShadowModeVersion.V1);
        assertThat(record.evidence()).isEqualTo(evidence);
        assertThat(record.decision()).isEqualTo(decision);
        assertThat(record.toReplayInput().evidence()).isEqualTo(evidence);
        assertThat(record.toReplayInput().context()).isEqualTo(context);
    }

    @Test
    void rejectsMismatchedPolicyVersion() {
        EvidenceVector evidence = DecisionGoldenFixtures.strongNormal();
        EvaluationContext context = DecisionGoldenFixtures.context();
        DecisionResult decision = engine.evaluate(evidence, context);
        LegacyPublicationOutcome legacy = new LegacyPublicationOutcome(
                ParkingSpotStatus.PENDING_VALIDATION, ParkingSpotStatus.ACTIVE, LegacyPublicationOutcome.Kind.APPLIED);
        ShadowDecisionComparison comparison = ShadowDecisionComparison.of(legacy, decision);
        var observation = DecisionCalibrationObservationFactory.from(
                evidence, decision, comparison, Duration.ofMillis(1), DecisionGoldenFixtures.T0);

        assertThatThrownBy(() -> DecisionAuditRecord.of(
                        UUID.randomUUID(),
                        evidence.parkingSpotId(),
                        evidence.evaluationId(),
                        "other-policy",
                        DecisionEngineVersion.V1,
                        ShadowModeVersion.V1,
                        context.evaluatedAt(),
                        evidence,
                        context,
                        decision,
                        legacy,
                        comparison.category(),
                        observation.riskBand(),
                        observation.hardConstraintFamily(),
                        observation.evidenceProfile(),
                        observation.decisiveRule(),
                        DecisionGoldenFixtures.T0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}