package com.parkio.parking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.domain.exception.ParkingException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-domain invariant tests for {@link ParkingSpot} creation. */
class ParkingSpotTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");

    private static ParkingSpot create(LegalStatus legalStatus, Set<VehicleType> vehicleTypes,
                                      double latitude, double longitude) {
        return ParkingSpot.create(UUID.randomUUID(), UUID.randomUUID(), latitude, longitude, null, null,
                false, vehicleTypes, ParkingContext.STREET_PARKING, legalStatus, Set.of(), NOW);
    }

    @Test
    void createsActiveSpotWithinDefaultWindow() {
        ParkingSpot spot = create(LegalStatus.LEGAL, Set.of(VehicleType.SEDAN), 41.0, 29.0);

        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(spot.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(spot.isVisibleForSearch(NOW)).isTrue();
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
        ParkingSpot spot = create(LegalStatus.LEGAL, Set.of(VehicleType.SEDAN), 41.0, 29.0);

        spot.verify(UUID.randomUUID(), VerificationResult.ILLEGAL_OR_RISKY, NOW.plusSeconds(1));

        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.SUSPICIOUS);
        assertThat(spot.confidenceScore()).isEqualTo(0.6);
        assertThat(spot.isTerminal()).isFalse();
    }

    @Test
    void moderatorRejectionIsAuthoritativeAndIdempotentForTerminalState() {
        ParkingSpot spot = create(LegalStatus.LEGAL, Set.of(VehicleType.SEDAN), 41.0, 29.0);
        spot.verify(UUID.randomUUID(), VerificationResult.ILLEGAL_OR_RISKY, NOW.plusSeconds(1));

        assertThat(spot.markRejectedByModerator(NOW.plusSeconds(2))).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.REJECTED);
        assertThat(spot.markRejectedByModerator(NOW.plusSeconds(3))).isFalse();
    }
}
