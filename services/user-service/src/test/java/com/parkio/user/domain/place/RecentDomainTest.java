package com.parkio.user.domain.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecentDomainTest {

    private static final UUID PROFILE = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    @Test
    void destinationRequiresLabelAndValidCoords() {
        assertThatThrownBy(() -> RecentDestination.create(
                        PROFILE, "  ", 38.4, 27.1, PlaceDestinationSource.MAP_PIN, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecentDestination.create(
                        PROFILE, "Alsancak", 91.0, 27.1, PlaceDestinationSource.MAP_PIN, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void turkishLabelPreservedAndDuplicateKeyUsesIdentityThenCoords() {
        RecentDestination withIdentity = RecentDestination.create(
                PROFILE,
                "Kordon İskele",
                38.430_123_4,
                27.140_987_6,
                PlaceDestinationSource.GEOCODING,
                PlaceIdentity.of("osm-nominatim", "N123"),
                "Alsancak",
                NOW);
        assertThat(withIdentity.label()).isEqualTo("Kordon İskele");
        assertThat(withIdentity.duplicateKey()).isEqualTo("identity:osm-nominatim:N123");

        RecentDestination coordsOnly = RecentDestination.create(
                PROFILE, "Pin", 38.430_123_4, 27.140_987_6, PlaceDestinationSource.MAP_PIN, null, null, NOW);
        assertThat(coordsOnly.duplicateKey()).isEqualTo("coord:38.43012:27.14099");
    }

    @Test
    void repeatConfirmationUpdatesRecencyUseCountAndDisplay() {
        RecentDestination recent = RecentDestination.create(
                PROFILE, "Kordon", 38.43, 27.14, PlaceDestinationSource.MAP_PIN, null, null, NOW);
        Instant later = NOW.plusSeconds(60);
        recent.recordConfirmation("Kordon Alsancak", "İzmir", later);
        assertThat(recent.label()).isEqualTo("Kordon Alsancak");
        assertThat(recent.subtitle()).isEqualTo("İzmir");
        assertThat(recent.lastUsedAt()).isEqualTo(later);
        assertThat(recent.useCount()).isEqualTo(2);
        assertThat(recent.firstUsedAt()).isEqualTo(NOW);
        assertThat(recent.latitude()).isEqualTo(38.43);
    }

    @Test
    void parkingSupportsMunicipalOnlyAndRecordsUse() {
        RecentParking parking = RecentParking.create(
                PROFILE, RecentParkingTargetKind.MUNICIPAL_FACILITY, PROFILE, NOW);
        parking.recordUse(NOW.plusSeconds(10));
        assertThat(parking.useCount()).isEqualTo(2);
        assertThatThrownBy(() -> new RecentParking(
                        UUID.randomUUID(),
                        PROFILE,
                        RecentParkingTargetKind.MUNICIPAL_FACILITY,
                        PROFILE,
                        NOW,
                        NOW,
                        0,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
