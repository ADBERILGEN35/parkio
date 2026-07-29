package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.audit.DecisionAuditRecord;
import com.parkio.parking.decision.audit.DecisionAuditRecordFactory;
import com.parkio.parking.decision.calibration.DecisionCalibrationObservationFactory;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.policy.DecisionEngine;
import com.parkio.parking.decision.policy.DecisionGoldenFixtures;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowDecisionComparison;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.infrastructure.persistence.audit.DecisionAuditSnapshotMapper;
import com.parkio.parking.infrastructure.persistence.entity.DecisionAuditEntity;
import com.parkio.parking.infrastructure.persistence.jpa.DecisionAuditJpaRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DecisionAuditRepositoryAdapterTest {

    private final DecisionAuditJpaRepository jpa = mock(DecisionAuditJpaRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final DecisionAuditRepositoryAdapter adapter =
            new DecisionAuditRepositoryAdapter(jpa, objectMapper);
    private final DecisionAuditSnapshotMapper mapper = new DecisionAuditSnapshotMapper(objectMapper);
    private final DecisionEngine engine = new DecisionEngine();

    @Test
    void appendPersistsImmutableEntityOnce() {
        DecisionAuditRecord record = sample();
        when(jpa.existsById(record.auditId())).thenReturn(false);

        adapter.append(record);

        ArgumentCaptor<DecisionAuditEntity> captor = ArgumentCaptor.forClass(DecisionAuditEntity.class);
        verify(jpa).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(record.auditId());
        assertThat(captor.getValue().getPolicyVersion()).isEqualTo("decision-shadow-v1");
        assertThat(captor.getValue().getSnapshotJson()).isNotBlank();
    }

    @Test
    void appendRefusesOverwrite() {
        DecisionAuditRecord record = sample();
        when(jpa.existsById(record.auditId())).thenReturn(true);

        assertThatThrownBy(() -> adapter.append(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable");
        verify(jpa, never()).save(any());
    }

    @Test
    void findByIdRoundTripsThroughMapper() {
        DecisionAuditRecord record = sample();
        when(jpa.findById(record.auditId())).thenReturn(Optional.of(mapper.toEntity(record)));

        Optional<DecisionAuditRecord> found = adapter.findById(record.auditId());
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().decision()).isEqualTo(record.decision());
    }

    @Test
    void queryMethodsDelegateToJpa() {
        when(jpa.findByParkingSpotIdOrderByEvaluatedAtAsc(any())).thenReturn(List.of());
        when(jpa.findByEvaluationIdOrderByEvaluatedAtAsc(any())).thenReturn(List.of());
        when(jpa.findByPolicyVersionOrderByEvaluatedAtAsc(any())).thenReturn(List.of());
        when(jpa.findByEvaluatedAtGreaterThanEqualAndEvaluatedAtLessThanOrderByEvaluatedAtAsc(any(), any()))
                .thenReturn(List.of());

        assertThat(adapter.findByParkingSpotId(UUID.randomUUID())).isEmpty();
        assertThat(adapter.findByEvaluationId(UUID.randomUUID())).isEmpty();
        assertThat(adapter.findByPolicyVersion("decision-shadow-v1")).isEmpty();
        assertThat(adapter.findByEvaluatedAtBetween(
                        DecisionGoldenFixtures.T0, DecisionGoldenFixtures.T0.plusSeconds(1)))
                .isEmpty();
    }

    private DecisionAuditRecord sample() {
        EvidenceVector evidence = DecisionGoldenFixtures.strongNormal();
        EvaluationContext context = DecisionGoldenFixtures.context();
        DecisionResult decision = engine.evaluate(evidence, context);
        LegacyPublicationOutcome legacy = new LegacyPublicationOutcome(
                ParkingSpotStatus.PENDING_VALIDATION, ParkingSpotStatus.ACTIVE, LegacyPublicationOutcome.Kind.APPLIED);
        ShadowDecisionComparison comparison = ShadowDecisionComparison.of(legacy, decision);
        var observation = DecisionCalibrationObservationFactory.from(
                evidence, decision, comparison, Duration.ofMillis(3), DecisionGoldenFixtures.T0);
        return DecisionAuditRecordFactory.fromSuccessfulShadow(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                evidence,
                context,
                decision,
                comparison,
                observation,
                DecisionGoldenFixtures.T0);
    }
}