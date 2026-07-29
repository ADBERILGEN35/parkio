package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.parkio.parking.application.port.DecisionAuditWriteObserver;
import com.parkio.parking.application.port.DecisionShadowObserverPort;
import com.parkio.parking.application.result.AiValidationApplyOutcome;
import com.parkio.parking.decision.application.EvidenceCollectionService;
import com.parkio.parking.decision.audit.DecisionAuditRecord;
import com.parkio.parking.decision.calibration.DecisionCalibrationObservation;
import com.parkio.parking.decision.calibration.ShadowFailureStage;
import com.parkio.parking.decision.normalization.AiValidationEvidenceInput;
import com.parkio.parking.decision.policy.DecisionEngine;
import com.parkio.parking.decision.port.DecisionAuditPort;
import com.parkio.parking.decision.port.EvidenceCollectionPort;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DecisionShadowOrchestratorTest {

    private static final Instant T0 = Instant.parse("2026-07-27T12:00:00Z");

    @Test
    void disabledFlagDoesNotEvaluateOrAudit() {
        RecordingObserver observer = new RecordingObserver();
        RecordingAudit audit = new RecordingAudit();
        DecisionShadowOrchestrator orchestrator = new DecisionShadowOrchestrator(
                false,
                new DecisionEngine(),
                new EvidenceCollectionService(),
                observer,
                audit,
                DecisionAuditWriteObserver.noop());

        orchestrator.observeAfterApply(sampleInput(), appliedActive(), T0);

        assertThat(observer.attempts.get()).isZero();
        assertThat(audit.appended).isEmpty();
    }

    @Test
    void enabledFlagRecordsCalibrationAndAppendsAudit() {
        RecordingObserver observer = new RecordingObserver();
        RecordingAudit audit = new RecordingAudit();
        RecordingAuditWriteObserver writeObserver = new RecordingAuditWriteObserver();
        DecisionShadowOrchestrator orchestrator = new DecisionShadowOrchestrator(
                true,
                new DecisionEngine(),
                new EvidenceCollectionService(),
                observer,
                audit,
                writeObserver);

        assertThatCode(() -> orchestrator.observeAfterApply(sampleInput(), appliedActive(), T0))
                .doesNotThrowAnyException();
        assertThat(observer.attempts.get()).isEqualTo(1);
        assertThat(observer.successes.get()).isEqualTo(1);
        assertThat(audit.appended).hasSize(1);
        assertThat(writeObserver.successes.get()).isEqualTo(1);
        assertThat(audit.appended.get(0).policyVersion()).isEqualTo("decision-shadow-v1");
    }

    @Test
    void auditFailureIsIsolatedAndDoesNotAffectPublicationPath() {
        RecordingObserver observer = new RecordingObserver();
        DecisionAuditPort failingAudit = new DecisionAuditPort() {
            @Override
            public void append(DecisionAuditRecord record) {
                throw new IllegalStateException("db down");
            }

            @Override
            public Optional<DecisionAuditRecord> findById(UUID auditId) {
                return Optional.empty();
            }

            @Override
            public List<DecisionAuditRecord> findByParkingSpotId(UUID parkingSpotId) {
                return List.of();
            }

            @Override
            public List<DecisionAuditRecord> findByEvaluationId(UUID evaluationId) {
                return List.of();
            }

            @Override
            public List<DecisionAuditRecord> findByPolicyVersion(String policyVersion) {
                return List.of();
            }

            @Override
            public List<DecisionAuditRecord> findByEvaluatedAtBetween(
                    Instant fromInclusive, Instant toExclusive) {
                return List.of();
            }
        };
        RecordingAuditWriteObserver writeObserver = new RecordingAuditWriteObserver();
        DecisionShadowOrchestrator orchestrator = new DecisionShadowOrchestrator(
                true,
                new DecisionEngine(),
                new EvidenceCollectionService(),
                observer,
                failingAudit,
                writeObserver);

        assertThatCode(() -> orchestrator.observeAfterApply(sampleInput(), appliedActive(), T0))
                .doesNotThrowAnyException();
        assertThat(observer.successes.get()).isEqualTo(1);
        assertThat(writeObserver.failures.get()).isEqualTo(1);
    }

    @Test
    void shadowCollectionFailureIsIsolatedWithStage() {
        RecordingObserver observer = new RecordingObserver();
        RecordingAudit audit = new RecordingAudit();
        EvidenceCollectionPort failing = request -> {
            throw new IllegalStateException("forced shadow failure");
        };
        DecisionShadowOrchestrator orchestrator = new DecisionShadowOrchestrator(
                true, new DecisionEngine(), failing, observer, audit, DecisionAuditWriteObserver.noop());

        assertThatCode(() -> orchestrator.observeAfterApply(sampleInput(), appliedActive(), T0))
                .doesNotThrowAnyException();
        assertThat(observer.failures.get()).isEqualTo(1);
        assertThat(observer.lastFailureStage.get()).isEqualTo(ShadowFailureStage.EVIDENCE_COLLECTION);
        assertThat(audit.appended).isEmpty();
    }

    @Test
    void observerFailureDoesNotPropagate() {
        DecisionShadowObserverPort exploding = new DecisionShadowObserverPort() {
            @Override
            public void recordAttempt() {
                throw new IllegalStateException("metrics down");
            }

            @Override
            public void recordSuccess(DecisionCalibrationObservation observation) {}

            @Override
            public void recordFailure(ShadowFailureStage stage, Duration duration) {}
        };
        DecisionShadowOrchestrator orchestrator = new DecisionShadowOrchestrator(
                true,
                new DecisionEngine(),
                new EvidenceCollectionService(),
                exploding,
                DecisionAuditPort.noop(),
                DecisionAuditWriteObserver.noop());

        assertThatCode(() -> orchestrator.observeAfterApply(sampleInput(), appliedActive(), T0))
                .doesNotThrowAnyException();
    }

    private static AiValidationEvidenceInput sampleInput() {
        return AiValidationEvidenceInput.of(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "PASSED",
                List.of(),
                90,
                10,
                85,
                80,
                T0);
    }

    private static AiValidationApplyOutcome appliedActive() {
        return new AiValidationApplyOutcome(
                ParkingSpotStatus.PENDING_VALIDATION,
                ParkingSpotStatus.ACTIVE,
                AiValidationApplyOutcome.Kind.APPLIED);
    }

    private static final class RecordingObserver implements DecisionShadowObserverPort {
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicReference<DecisionCalibrationObservation> lastObservation = new AtomicReference<>();
        private final AtomicReference<ShadowFailureStage> lastFailureStage = new AtomicReference<>();

        @Override
        public void recordAttempt() {
            attempts.incrementAndGet();
        }

        @Override
        public void recordSuccess(DecisionCalibrationObservation observation) {
            successes.incrementAndGet();
            lastObservation.set(observation);
        }

        @Override
        public void recordFailure(ShadowFailureStage stage, Duration duration) {
            failures.incrementAndGet();
            lastFailureStage.set(stage);
        }
    }

    private static final class RecordingAudit implements DecisionAuditPort {
        private final List<DecisionAuditRecord> appended = new ArrayList<>();

        @Override
        public void append(DecisionAuditRecord record) {
            appended.add(record);
        }

        @Override
        public Optional<DecisionAuditRecord> findById(UUID auditId) {
            return appended.stream().filter(r -> r.auditId().equals(auditId)).findFirst();
        }

        @Override
        public List<DecisionAuditRecord> findByParkingSpotId(UUID parkingSpotId) {
            return appended.stream().filter(r -> r.parkingSpotId().equals(parkingSpotId)).toList();
        }

        @Override
        public List<DecisionAuditRecord> findByEvaluationId(UUID evaluationId) {
            return appended.stream().filter(r -> r.evaluationId().equals(evaluationId)).toList();
        }

        @Override
        public List<DecisionAuditRecord> findByPolicyVersion(String policyVersion) {
            return appended.stream().filter(r -> r.policyVersion().equals(policyVersion)).toList();
        }

        @Override
        public List<DecisionAuditRecord> findByEvaluatedAtBetween(
                Instant fromInclusive, Instant toExclusive) {
            return appended.stream()
                    .filter(r -> !r.evaluatedAt().isBefore(fromInclusive) && r.evaluatedAt().isBefore(toExclusive))
                    .toList();
        }
    }

    private static final class RecordingAuditWriteObserver implements DecisionAuditWriteObserver {
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public void onWriteSuccess() {
            successes.incrementAndGet();
        }

        @Override
        public void onWriteFailure() {
            failures.incrementAndGet();
        }
    }
}