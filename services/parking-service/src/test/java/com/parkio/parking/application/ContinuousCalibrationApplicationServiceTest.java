package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.calibration.CalibrationProcessingResult;
import com.parkio.parking.application.calibration.FraudLedgerCalibrationCandidate;
import com.parkio.parking.application.calibration.TrustOutcomeCalibrationPair;
import com.parkio.parking.application.port.CalibrationObservationPort;
import com.parkio.parking.application.port.CalibrationReadinessPort;
import com.parkio.parking.application.port.CalibrationReportPort;
import com.parkio.parking.application.port.ContinuousCalibrationObserverPort;
import com.parkio.parking.application.port.FraudLedgerCalibrationReadPort;
import com.parkio.parking.application.port.TrustOutcomeCalibrationReadPort;
import com.parkio.parking.calibration.CalibrationEngineType;
import com.parkio.parking.calibration.CalibrationObservation;
import com.parkio.parking.calibration.CalibrationReadinessAssessment;
import com.parkio.parking.calibration.CalibrationReport;
import com.parkio.parking.fraud.FraudConfidenceBand;
import com.parkio.parking.fraud.FraudDisposition;
import com.parkio.parking.fraud.FraudRiskBand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContinuousCalibrationApplicationServiceTest {

    @Test
    void trustPairProducesObservationAppended() {
        RecordingObservationPort observations = new RecordingObservationPort(false);
        TrustOutcomeCalibrationPair pair = trustPair();
        ContinuousCalibrationApplicationService service = service(
                observations,
                List.of(pair),
                List.of());

        CalibrationProcessingResult result = service.processTrustBatch(1);

        assertThat(result.status()).isEqualTo(CalibrationProcessingResult.Status.REPORT_GENERATED);
        assertThat(result.appendedCount()).isEqualTo(1);
        assertThat(observations.observations).hasSize(1);
        assertThat(observations.observations.getFirst().engineType()).isEqualTo(CalibrationEngineType.TRUST);
    }

    @Test
    void duplicateObservationIdempotent() {
        TrustOutcomeCalibrationPair pair = trustPair();
        ContinuousCalibrationApplicationService service = service(
                new RecordingObservationPort(true),
                List.of(pair, pair),
                List.of());

        CalibrationProcessingResult result = service.processTrustBatch(2);

        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(result.appendedCount()).isEqualTo(1);
    }

    @Test
    void fraudCandidateProducesObservation() {
        RecordingObservationPort observations = new RecordingObservationPort(false);
        FraudLedgerCalibrationCandidate candidate = fraudCandidate();
        ContinuousCalibrationApplicationService service = service(
                observations,
                List.of(),
                List.of(candidate));

        CalibrationProcessingResult result = service.processFraudBatch(1);

        assertThat(result.status()).isEqualTo(CalibrationProcessingResult.Status.REPORT_GENERATED);
        assertThat(result.appendedCount()).isEqualTo(1);
        assertThat(observations.observations).hasSize(1);
        assertThat(observations.observations.getFirst().engineType()).isEqualTo(CalibrationEngineType.FRAUD);
    }

    private static ContinuousCalibrationApplicationService service(
            RecordingObservationPort observations,
            List<TrustOutcomeCalibrationPair> trustPairs,
            List<FraudLedgerCalibrationCandidate> fraudCandidates) {
        return new ContinuousCalibrationApplicationService(
                observations,
                new RecordingReportPort(),
                new RecordingReadinessPort(),
                limit -> trustPairs,
                limit -> fraudCandidates,
                ContinuousCalibrationObserverPort.noop(),
                Clock.fixed(Instant.parse("2026-07-28T11:00:00Z"), ZoneOffset.UTC));
    }

    private static TrustOutcomeCalibrationPair trustPair() {
        Instant evaluatedAt = Instant.parse("2026-07-28T10:00:00Z");
        return new TrustOutcomeCalibrationPair(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "trust-policy-v1",
                "ESTABLISHED",
                "HIGH",
                "CONFIRMED_CORRECT",
                "MULTIPLE_AVAILABLE_VERIFICATIONS",
                "DIRECT",
                evaluatedAt,
                evaluatedAt);
    }

    private static FraudLedgerCalibrationCandidate fraudCandidate() {
        Instant evaluatedAt = Instant.parse("2026-07-28T10:00:00Z");
        return new FraudLedgerCalibrationCandidate(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                "fraud-policy-v1",
                FraudRiskBand.HIGH,
                FraudConfidenceBand.HIGH,
                FraudDisposition.REVIEW_CANDIDATE,
                3,
                "CONFIRMED_INCORRECT",
                evaluatedAt);
    }

    private static final class RecordingObservationPort implements CalibrationObservationPort {

        private final boolean duplicateOnAppend;
        private final List<CalibrationObservation> observations = new ArrayList<>();
        private final Set<UUID> observationIds = new HashSet<>();

        private RecordingObservationPort(boolean duplicateOnAppend) {
            this.duplicateOnAppend = duplicateOnAppend;
        }

        @Override
        public void append(CalibrationObservation observation) {
            if (duplicateOnAppend && !observationIds.add(observation.observationId())) {
                throw new DuplicateCalibrationObservationException("duplicate");
            }
            observationIds.add(observation.observationId());
            observations.add(observation);
        }

        @Override
        public Optional<CalibrationObservation> findByObservationId(UUID observationId) {
            return observations.stream()
                    .filter(observation -> observation.observationId().equals(observationId))
                    .findFirst();
        }

        @Override
        public List<CalibrationObservation> findByEngineAndWindow(
                CalibrationEngineType engineType, Instant windowStart, Instant windowEnd) {
            return observations.stream()
                    .filter(observation -> observation.engineType() == engineType)
                    .filter(observation -> !observation.predictedAt().isBefore(windowStart))
                    .filter(observation -> !observation.predictedAt().isAfter(windowEnd))
                    .toList();
        }
    }

    private static final class RecordingReportPort implements CalibrationReportPort {

        private final List<CalibrationReport> reports = new ArrayList<>();

        @Override
        public void append(CalibrationReport report) {
            reports.add(report);
        }

        @Override
        public Optional<CalibrationReport> findByReportId(UUID reportId) {
            return reports.stream().filter(report -> report.reportId().equals(reportId)).findFirst();
        }

        @Override
        public Optional<CalibrationReport> findLatestByEngineAndPolicy(
                CalibrationEngineType engineType, String policyVersion) {
            return reports.stream()
                    .filter(report -> report.engineType() == engineType)
                    .filter(report -> report.baselinePolicyVersion().orElse("").equals(policyVersion))
                    .reduce((first, second) -> second);
        }
    }

    private static final class RecordingReadinessPort implements CalibrationReadinessPort {

        @Override
        public void append(CalibrationReadinessAssessment assessment) {}

        @Override
        public Optional<CalibrationReadinessAssessment> findByAssessmentId(UUID assessmentId) {
            return Optional.empty();
        }
    }
}
