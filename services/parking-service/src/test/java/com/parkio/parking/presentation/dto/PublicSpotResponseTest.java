package com.parkio.parking.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Guards that the public spot view never exposes owner identity or moderation signals. */
class PublicSpotResponseTest {

    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "owneruserid", "confidencescore", "verificationcount", "filledreportcount");

    @Test
    void publicResponseHasNoOwnerOrModerationFields() {
        for (RecordComponent component : PublicSpotResponse.class.getRecordComponents()) {
            assertThat(FORBIDDEN_FIELDS).doesNotContain(component.getName().toLowerCase());
        }
    }

    @Test
    void mappingExposesOnlyPublicData() {
        UUID ownerUserId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-07T12:00:00Z");
        ParkingSpot spot = new ParkingSpot(UUID.randomUUID(), ownerUserId, UUID.randomUUID(), 41.0, 29.0,
                "Main St", "Nice spot", false, Set.of(VehicleType.SEDAN), ParkingContext.STREET_PARKING,
                LegalStatus.LEGAL, Set.of(), ParkingSpotStatus.VERIFIED, 0.4, 3, 1,
                now.plusSeconds(600), now, now, 0L);

        PublicSpotResponse response = PublicSpotResponse.from(spot);

        assertThat(response.id()).isEqualTo(spot.id());
        assertThat(response.status()).isEqualTo("VERIFIED");
        // The owner id is not reachable through any accessor of the public record.
        boolean exposesOwner = Arrays.stream(PublicSpotResponse.class.getRecordComponents())
                .anyMatch(c -> c.getName().equalsIgnoreCase("ownerUserId"));
        assertThat(exposesOwner).isFalse();
    }
}
