package com.parkio.parking.decision.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.domain.ParkingSpotStatus;
import org.junit.jupiter.api.Test;

class PublicationDispositionCompatibilityTest {

    @Test
    void conservativeLegacyMappings() {
        assertThat(PublicationDispositionCompatibility.suggestedLegacyStatus(PublicationDisposition.FULL_PUBLISH))
                .contains(ParkingSpotStatus.ACTIVE);
        assertThat(PublicationDispositionCompatibility.suggestedLegacyStatus(PublicationDisposition.HOLD))
                .contains(ParkingSpotStatus.PENDING_VALIDATION);
        assertThat(PublicationDispositionCompatibility.suggestedLegacyStatus(PublicationDisposition.EXPIRED))
                .contains(ParkingSpotStatus.EXPIRED);
        assertThat(PublicationDispositionCompatibility.suggestedLegacyStatus(PublicationDisposition.REJECTED))
                .contains(ParkingSpotStatus.REJECTED);
    }

    @Test
    void limitedAndShadowHaveNoSafeLegacyEquivalent() {
        assertThat(PublicationDispositionCompatibility.suggestedLegacyStatus(PublicationDisposition.LIMITED_PUBLISH))
                .isEmpty();
        assertThat(PublicationDispositionCompatibility.suggestedLegacyStatus(PublicationDisposition.SHADOW))
                .isEmpty();
    }
}