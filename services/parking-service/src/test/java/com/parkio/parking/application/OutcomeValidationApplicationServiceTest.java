package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.outcome.OutcomeEvaluationIdentity;
import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.application.outcome.OutcomeEvaluationTriggerRequest;
import com.parkio.parking.application.outcome.OutcomeProcessingResult;
import com.parkio.parking.outcome.normalization.OutcomeSpotSnapshotData;
import com.parkio.parking.outcome.normalization.OutcomeVerificationSignalData;
import com.parkio.parking.application.port.OutcomeOperationalizationObserverPort;
import com.parkio.parking.application.port.OutcomeSpotSnapshotReadPort;
import com.parkio.parking.application.port.OutcomeStatusHistoryReadPort;
import com.parkio.parking.application.port.OutcomeVerificationReadPort;
import com.parkio.parking.domain.ModerationPolicy;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.VerificationResult;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import com.parkio.parking.outcome.port.OutcomeHistoryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutcomeValidationApplicationServiceTest {

    @Test
    void returnsIneligibleWhenSpotWasNeverPublished() {
        UUID spotId = UUID.randomUUID();
        Instant cutoff = Instant.parse("2026-07-28T10:00:00Z");
        var service = new OutcomeValidationApplicationService(
                id -> Optional.of(new OutcomeSpotSnapshotData(id, cutoff.minusSeconds(30))),
                (id, ignored) -> List.of(),
                (id, ignored) -> List.of(),
                new InMemoryOutcomeHistoryPort(false),
                OutcomeOperationalizationObserverPort.noop(),
                moderationPolicy(),
                fixedClock(cutoff));

        OutcomeProcessingResult result = service.process(trigger(spotId, OutcomeEvaluationTrigger.CLAIM, UUID.randomUUID(), cutoff));

        assertThat(result.status()).isEqualTo(OutcomeProcessingResult.Status.INELIGIBLE);
    }

    @Test
    void appendsOutcomeHistoryForEligibleTrigger() {
        UUID spotId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-28T10:00:00Z");
        Instant publishedAt = createdAt.plusSeconds(30);
        Instant cutoff = publishedAt.plusSeconds(60);
        InMemoryOutcomeHistoryPort history = new InMemoryOutcomeHistoryPort(false);
        var service = new OutcomeValidationApplicationService(
                id -> Optional.of(new OutcomeSpotSnapshotData(id, createdAt)),
                (id, ignored) -> List.of(new ParkingSpotStatusHistory(UUID.randomUUID(), id, ParkingSpotStatus.PENDING_VALIDATION,
                        ParkingSpotStatus.ACTIVE, "AI_PASSED", publishedAt)),
                (id, ignored) -> List.of(new OutcomeVerificationSignalData(UUID.randomUUID(), VerificationResult.AVAILABLE, cutoff)),
                history,
                OutcomeOperationalizationObserverPort.noop(),
                moderationPolicy(),
                fixedClock(cutoff));

        OutcomeProcessingResult result = service.process(trigger(spotId, OutcomeEvaluationTrigger.VERIFICATION_AVAILABLE, UUID.randomUUID(), cutoff));

        assertThat(result.status()).isEqualTo(OutcomeProcessingResult.Status.APPENDED);
        assertThat(history.records).hasSize(1);
        assertThat(history.records.getFirst().evidenceCutoffAt()).isEqualTo(cutoff);
    }

    @Test
    void reportsDuplicateWhenHistoryAlreadyExists() {
        UUID spotId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-28T10:00:00Z");
        Instant publishedAt = createdAt.plusSeconds(30);
        Instant cutoff = publishedAt.plusSeconds(60);
        var service = new OutcomeValidationApplicationService(
                id -> Optional.of(new OutcomeSpotSnapshotData(id, createdAt)),
                (id, ignored) -> List.of(new ParkingSpotStatusHistory(UUID.randomUUID(), id, ParkingSpotStatus.PENDING_VALIDATION,
                        ParkingSpotStatus.ACTIVE, "AI_PASSED", publishedAt)),
                (id, ignored) -> List.of(),
                new InMemoryOutcomeHistoryPort(true),
                OutcomeOperationalizationObserverPort.noop(),
                moderationPolicy(),
                fixedClock(cutoff));

        OutcomeProcessingResult result = service.process(trigger(spotId, OutcomeEvaluationTrigger.PUBLICATION, UUID.randomUUID(), cutoff));

        assertThat(result.status()).isEqualTo(OutcomeProcessingResult.Status.DUPLICATE);
    }

    private static OutcomeEvaluationTriggerRequest trigger(UUID spotId, OutcomeEvaluationTrigger triggerType, UUID triggerRef, Instant cutoff) {
        return new OutcomeEvaluationTriggerRequest(
                OutcomeEvaluationIdentity.forTrigger(spotId, triggerType, triggerRef, cutoff),
                spotId,
                triggerType,
                triggerRef,
                cutoff,
                cutoff);
    }

    private static ModerationPolicy moderationPolicy() {
        return new ModerationPolicy(
                Duration.ofMinutes(10),
                Duration.ofMinutes(2),
                Duration.ofMinutes(1),
                3,
                Duration.ofMinutes(15),
                Duration.ofMinutes(30));
    }

    private static Clock fixedClock(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private static final class InMemoryOutcomeHistoryPort implements OutcomeHistoryPort {
        private final boolean duplicate;
        private final List<OutcomeHistoryRecord> records = new ArrayList<>();

        private InMemoryOutcomeHistoryPort(boolean duplicate) {
            this.duplicate = duplicate;
        }

        @Override
        public void append(OutcomeHistoryRecord record) {
            if (duplicate) {
                throw new DuplicateOutcomeHistoryException("duplicate");
            }
            records.add(record);
        }

        @Override
        public Optional<OutcomeHistoryRecord> findLatest(UUID parkingSpotId) {
            return records.stream().filter(record -> record.parkingSpotId().equals(parkingSpotId)).reduce((a, b) -> b);
        }

        @Override
        public Optional<OutcomeHistoryRecord> findLatestAtOrBefore(UUID parkingSpotId, Instant cutoffInclusive) {
            return records.stream()
                    .filter(record -> record.parkingSpotId().equals(parkingSpotId))
                    .filter(record -> !record.evaluatedAt().isAfter(cutoffInclusive))
                    .reduce((a, b) -> b);
        }

        @Override
        public Optional<OutcomeHistoryRecord> findByEvaluationId(UUID evaluationId) {
            return records.stream().filter(record -> record.evaluationId().equals(evaluationId)).findFirst();
        }

        @Override
        public List<OutcomeHistoryRecord> findAll(UUID parkingSpotId) {
            return records.stream().filter(record -> record.parkingSpotId().equals(parkingSpotId)).toList();
        }
    }
}