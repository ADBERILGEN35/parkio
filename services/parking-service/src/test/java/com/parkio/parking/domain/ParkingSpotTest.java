package com.parkio.parking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.domain.exception.ParkingException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-domain invariant tests for {@link ParkingSpot} creation and AI gate. */
class ParkingSpotTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");

    private static ParkingSpot create(LegalStatus legalStatus, Set<VehicleType> vehicleTypes,
                                      double latitude, double longitude) {
        return ParkingSpot.create(UUID.randomUUID(), UUID.randomUUID(), latitude, longitude, null, null,
                false, vehicleTypes, ParkingContext.STREET_PARKING, legalStatus, Set.of(), NOW);
    }

    private static ParkingSpot createActive() {
        ParkingSpot spot = create(LegalStatus.LEGAL, Set.of(VehicleType.SEDAN), 41.0, 29.0);
        assertThat(spot.applyAiValidationPassed(NOW)).isTrue();
        return spot;
    }

    @Test
    void createsPendingValidationSpotHiddenFromSearch() {
        ParkingSpot spot = create(LegalStatus.LEGAL, Set.of(VehicleType.SEDAN), 41.0, 29.0);

        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);
        assertThat(spot.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(spot.isVisibleForSearch(NOW)).isFalse();
        assertThat(spot.isTerminal()).isFalse();
    }

    @Test
    void applyAiValidationPassedPromotesToActiveAndVisible() {
        ParkingSpot spot = create(LegalStatus.LEGAL, Set.of(VehicleType.SEDAN), 41.0, 29.0);

        assertThat(spot.applyAiValidationPassed(NOW.plusSeconds(1))).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(spot.isVisibleForSearch(NOW.plusSeconds(1))).isTrue();
        assertThat(spot.applyAiValidationPassed(NOW.plusSeconds(2))).isFalse();
    }

    @Test
    void applyAiValidationUncertainMovesToPendingReviewStillHidden() {
        ParkingSpot spot = create(LegalStatus.LEGAL, Set.of(VehicleType.SEDAN), 41.0, 29.0);

        assertThat(spot.applyAiValidationUncertain(NOW.plusSeconds(1))).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.PENDING_REVIEW);
        assertThat(spot.isVisibleForSearch(NOW.plusSeconds(1))).isFalse();
        assertThat(spot.applyAiValidationUncertain(NOW.plusSeconds(2))).isFalse();

        assertThat(spot.applyAiValidationPassed(NOW.plusSeconds(3))).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
    }

    @Test
    void applyAiValidationRejectedIsTerminalAndHidden() {
        ParkingSpot spot = create(LegalStatus.LEGAL, Set.of(VehicleType.SEDAN), 41.0, 29.0);

        assertThat(spot.applyAiValidationRejected(NOW.plusSeconds(1))).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.REJECTED);
        assertThat(spot.isTerminal()).isTrue();
        assertThat(spot.isVisibleForSearch(NOW.plusSeconds(1))).isFalse();
        assertThat(spot.applyAiValidationRejected(NOW.plusSeconds(2))).isFalse();
    }

    @Test
    void rejectsIllegalOrRiskyCreation() {
        assertThatThrownBy(() -> create(LegalStatus.ILLEGAL_OR_RISKY, Set.of(VehicleType.SEDAN), 41.0, 29.0))
                .isInstanceOf(ParkingException.class);
    }

    @Test
    void requiresAtLeastOneVehicleType() {
        assertThatThrownBy(() -> create(LegalStatus.LEGAL, Set.of(), 41.0, 29.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfRangeCoordinates() {
        assertThatThrownBy(() -> create(LegalStatus.LEGAL, Set.of(VehicleType.ANY), 91.0, 29.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(LegalStatus.LEGAL, Set.of(VehicleType.ANY), 41.0, 181.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void illegalRiskVerificationMarksSuspiciousAndReducesConfidence() {
        ParkingSpot spot = createActive();

        spot.verify(UUID.randomUUID(), VerificationResult.ILLEGAL_OR_RISKY, NOW.plusSeconds(1));

        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.SUSPICIOUS);
        assertThat(spot.confidenceScore()).isEqualTo(0.6);
        assertThat(spot.isTerminal()).isFalse();
    }

    @Test
    void pendingValidationCannotBeVerifiedOrClaimed() {
        ParkingSpot spot = create(LegalStatus.LEGAL, Set.of(VehicleType.SEDAN), 41.0, 29.0);

        assertThatThrownBy(() -> spot.verify(UUID.randomUUID(), VerificationResult.FILLED, NOW.plusSeconds(1)))
                .isInstanceOf(ParkingException.class);
        assertThatThrownBy(() -> spot.claim(UUID.randomUUID(), NOW.plusSeconds(1)))
                .isInstanceOf(ParkingException.class);
    }

    @Test
    void moderatorRejectionIsAuthoritativeAndIdempotentForTerminalState() {
        ParkingSpot spot = createActive();
        spot.verify(UUID.randomUUID(), VerificationResult.ILLEGAL_OR_RISKY, NOW.plusSeconds(1));

        assertThat(spot.markRejectedByModerator(NOW.plusSeconds(2))).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.REJECTED);
        assertThat(spot.markRejectedByModerator(NOW.plusSeconds(3))).isFalse();
    }
}
