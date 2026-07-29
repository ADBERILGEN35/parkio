package com.parkio.parking.infrastructure.persistence.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.audit.DecisionAuditRecord;
import com.parkio.parking.decision.audit.DecisionAuditRecordFactory;
import com.parkio.parking.decision.audit.DecisionAuditReplayer;
import com.parkio.parking.decision.calibration.DecisionCalibrationObservationFactory;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.policy.DecisionEngine;
import com.parkio.parking.decision.policy.DecisionGoldenFixtures;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowDecisionComparison;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.infrastructure.persistence.entity.DecisionAuditEntity;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DecisionAuditSnapshotMapperTest {

    private final DecisionAuditSnapshotMapper mapper =
            new DecisionAuditSnapshotMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final DecisionEngine engine = new DecisionEngine();

    @Test
    void roundTripPreservesCanonicalDomainAndReplayIdentity() {
        DecisionAuditRecord original = sample();
        DecisionAuditEntity entity = mapper.toEntity(original);
        DecisionAuditRecord restored = mapper.toDomain(entity);

        assertThat(restored.auditId()).isEqualTo(original.auditId());
        assertThat(restored.evidence()).isEqualTo(original.evidence());
        assertThat(restored.decision()).isEqualTo(original.decision());
        assertThat(restored.comparisonCategory()).isEqualTo(original.comparisonCategory());
        assertThat(restored.riskBand()).isEqualTo(original.riskBand());
        assertThat(entity.getSnapshotJson()).doesNotContain("latitude");
        assertThat(entity.getSnapshotJson()).doesNotContain("longitude");
        assertThat(entity.getSnapshotJson().toLowerCase()).doesNotContain("stacktrace");
        assertThat(DecisionAuditReplayer.replayAndCompare(restored).identical()).isTrue();
    }

    @Test
    void refusesRawPayloadMarkers() {
        DecisionAuditEntity entity = mapper.toEntity(sample());
        String json = entity.getSnapshotJson();
        assertThat(json).doesNotContain("detectedRiskTypes");
        assertThat(json).doesNotContain("emptySpaceConfidence");
        assertThat(json).doesNotContain("Kafka");
    }

    private DecisionAuditRecord sample() {
        EvidenceVector evidence = DecisionGoldenFixtures.strongNormal();
        EvaluationContext context = DecisionGoldenFixtures.context();
        DecisionResult decision = engine.evaluate(evidence, context);
        LegacyPublicationOutcome legacy = new LegacyPublicationOutcome(
                ParkingSpotStatus.PENDING_VALIDATION, ParkingSpotStatus.ACTIVE, LegacyPublicationOutcome.Kind.APPLIED);
        ShadowDecisionComparison comparison = ShadowDecisionComparison.of(legacy, decision);
        var observation = DecisionCalibrationObservationFactory.from(
                evidence, decision, comparison, Duration.ofMillis(8), DecisionGoldenFixtures.T0);
        return DecisionAuditRecordFactory.fromSuccessfulShadow(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                evidence,
                context,
                decision,
                comparison,
                observation,
                DecisionGoldenFixtures.T0);
    }
}