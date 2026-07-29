package com.parkio.parking.outcome.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.outcome.normalization.OutcomeSpotSnapshotData;
import com.parkio.parking.outcome.normalization.OutcomeVerificationSignalData;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.VerificationResult;
import com.parkio.parking.outcome.signal.OutcomeSignalType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutcomeHistoricalEvidenceFactoryTest {

    @Test
    void reconstructsPublishedStateAndVerificationExtensionsAtCutoff() {
        UUID spotId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-28T10:00:00Z");
        Instant publishedAt = createdAt.plusSeconds(30);
        Instant verifyOneAt = publishedAt.plusSeconds(60);
        Instant verifyTwoAt = publishedAt.plusSeconds(120);

        var evidence = OutcomeHistoricalEvidenceFactory.create(
                new OutcomeSpotSnapshotData(spotId, createdAt),
                Duration.ofMinutes(10),
                List.of(new ParkingSpotStatusHistory(UUID.randomUUID(), spotId, ParkingSpotStatus.PENDING_VALIDATION,
                        ParkingSpotStatus.ACTIVE, "AI_PASSED", publishedAt)),
                List.of(
                        new OutcomeVerificationSignalData(UUID.randomUUID(), VerificationResult.AVAILABLE, verifyOneAt),
                        new OutcomeVerificationSignalData(UUID.randomUUID(), VerificationResult.AVAILABLE, verifyTwoAt)));

        assertThat(evidence.isPublished()).isTrue();
        assertThat(evidence.status()).isEqualTo(ParkingSpotStatus.VERIFIED);
        assertThat(evidence.verificationCount()).isEqualTo(2);
        assertThat(evidence.expiresAt()).isEqualTo(verifyTwoAt.plus(Duration.ofMinutes(20)));
        assertThat(evidence.timeline().countSignalType(OutcomeSignalType.VERIFICATION_AVAILABLE)).isEqualTo(2);
    }

    @Test
    void reconstructsNegativeSignalsWithoutDoubleCountingStatusHistory() {
        UUID spotId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-28T10:00:00Z");
        Instant publishedAt = createdAt.plusSeconds(30);
        Instant negativeAt = publishedAt.plusSeconds(60);

        var evidence = OutcomeHistoricalEvidenceFactory.create(
                new OutcomeSpotSnapshotData(spotId, createdAt),
                Duration.ofMinutes(10),
                List.of(
                        new ParkingSpotStatusHistory(UUID.randomUUID(), spotId, ParkingSpotStatus.PENDING_VALIDATION,
                                ParkingSpotStatus.ACTIVE, "AI_PASSED", publishedAt),
                        new ParkingSpotStatusHistory(UUID.randomUUID(), spotId, ParkingSpotStatus.ACTIVE,
                                ParkingSpotStatus.SUSPICIOUS, "VERIFICATION_INVALID", negativeAt)),
                List.of(new OutcomeVerificationSignalData(UUID.randomUUID(), VerificationResult.INVALID, negativeAt)));

        assertThat(evidence.filledReportCount()).isZero();
        assertThat(evidence.confidenceScore()).isEqualTo(0.6);
        assertThat(evidence.timeline().countSignalType(OutcomeSignalType.VERIFICATION_INVALID)).isEqualTo(1);
    }
}