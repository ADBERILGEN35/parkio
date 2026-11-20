package com.parkio.parking.outcome.normalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.parkio.parking.outcome.normalization.OutcomeSpotSnapshotData;
import com.parkio.parking.outcome.normalization.OutcomeVerificationSignalData;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.VerificationResult;
import com.parkio.parking.outcome.signal.OutcomeSignal;
import com.parkio.parking.outcome.signal.OutcomeSignalSource;
import com.parkio.parking.outcome.signal.OutcomeSignalType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @ParameterizedTest
    @EnumSource(VerificationResult.class)
    void reconstructsOneCommunitySignalForEveryVerificationResult(VerificationResult result) {
        UUID spotId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-28T10:00:00Z");
        Instant publishedAt = createdAt.plusSeconds(30);
        Instant verifiedAt = publishedAt.plusSeconds(60);
        Duration activeDuration = Duration.ofMinutes(10);
        Instant publishedExpiry = publishedAt.plus(activeDuration);

        var evidence = OutcomeHistoricalEvidenceFactory.create(
                new OutcomeSpotSnapshotData(spotId, createdAt),
                activeDuration,
                List.of(new ParkingSpotStatusHistory(UUID.randomUUID(), spotId, ParkingSpotStatus.PENDING_VALIDATION,
                        ParkingSpotStatus.ACTIVE, "AI_PASSED", publishedAt)),
                List.of(new OutcomeVerificationSignalData(UUID.randomUUID(), result, verifiedAt)));

        // One verification on an ACTIVE spot. WRONG_VEHICLE_SIZE is scored like INVALID but keeps its own signal.
        Expected expected = switch (result) {
            case AVAILABLE -> new Expected(OutcomeSignalType.VERIFICATION_AVAILABLE,
                    ParkingSpotStatus.VERIFIED, 1, 0, 1.0, verifiedAt.plus(Duration.ofMinutes(15)));
            case FILLED -> new Expected(OutcomeSignalType.VERIFICATION_FILLED,
                    ParkingSpotStatus.SUSPICIOUS, 0, 1, 1.0, publishedExpiry);
            case INVALID -> new Expected(OutcomeSignalType.VERIFICATION_INVALID,
                    ParkingSpotStatus.ACTIVE, 0, 0, 0.6, publishedExpiry);
            case ILLEGAL_OR_RISKY -> new Expected(OutcomeSignalType.VERIFICATION_ILLEGAL_OR_RISKY,
                    ParkingSpotStatus.SUSPICIOUS, 0, 0, 0.6, publishedExpiry);
            case WRONG_VEHICLE_SIZE -> new Expected(OutcomeSignalType.VERIFICATION_WRONG_VEHICLE_SIZE,
                    ParkingSpotStatus.ACTIVE, 0, 0, 0.6, publishedExpiry);
        };
        assertThat(evidence.timeline().signals())
                .extracting(OutcomeSignal::type, OutcomeSignal::source, OutcomeSignal::occurredAt)
                .containsExactly(
                        tuple(OutcomeSignalType.AI_PUBLISHED, OutcomeSignalSource.SYSTEM, publishedAt),
                        tuple(expected.signal(), OutcomeSignalSource.COMMUNITY, verifiedAt));
        assertThat(evidence.status()).isEqualTo(expected.status());
        assertThat(evidence.verificationCount()).isEqualTo(expected.verificationCount());
        assertThat(evidence.filledReportCount()).isEqualTo(expected.filledReportCount());
        assertThat(evidence.confidenceScore()).isEqualTo(expected.confidenceScore());
        assertThat(evidence.expiresAt()).isEqualTo(expected.expiresAt());
        assertThat(evidence.updatedAt()).isEqualTo(verifiedAt);
    }

    private record Expected(
            OutcomeSignalType signal,
            ParkingSpotStatus status,
            int verificationCount,
            int filledReportCount,
            double confidenceScore,
            Instant expiresAt) {}
}