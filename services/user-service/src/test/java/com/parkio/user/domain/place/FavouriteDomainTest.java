package com.parkio.user.domain.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FavouriteDomainTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID PROFILE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID FACILITY = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    @Test
    void parkingFavouriteValid() {
        FavouriteParking fav = FavouriteParking.create(
                PROFILE, FavouriteParkingTargetKind.MUNICIPAL_FACILITY, FACILITY, NOW);
        assertThat(fav.targetId()).isEqualTo(FACILITY);
        assertThat(fav.targetKind()).isEqualTo(FavouriteParkingTargetKind.MUNICIPAL_FACILITY);
    }

    @Test
    void destinationDuplicateKeyPrefersIdentity() {
        PlaceIdentity identity = PlaceIdentity.of("osm-nominatim", "N99");
        FavouriteDestination fav = FavouriteDestination.create(
                PROFILE, "Forum Bornova", 38.45001, 27.21001,
                PlaceDestinationSource.GEOCODING, identity, null, NOW);
        assertThat(fav.duplicateKey()).isEqualTo("identity:osm-nominatim:N99");
    }

    @Test
    void destinationDuplicateKeyUsesFiveDecimalCoords() {
        FavouriteDestination a = FavouriteDestination.create(
                PROFILE, "A", 38.450001, 27.210001, PlaceDestinationSource.MAP_PIN, null, null, NOW);
        FavouriteDestination b = FavouriteDestination.create(
                PROFILE, "B", 38.450004, 27.210004, PlaceDestinationSource.MAP_PIN, null, null, NOW);
        assertThat(a.duplicateKey()).isEqualTo(b.duplicateKey());
        assertThat(a.duplicateKey()).startsWith("coord:");
    }

    @Test
    void nearbyDistinctDestinationsRemainSeparate() {
        FavouriteDestination a = FavouriteDestination.create(
                PROFILE, "A", 38.45000, 27.21000, PlaceDestinationSource.MAP_PIN, null, null, NOW);
        FavouriteDestination b = FavouriteDestination.create(
                PROFILE, "B", 38.45100, 27.21000, PlaceDestinationSource.MAP_PIN, null, null, NOW);
        assertThat(a.duplicateKey()).isNotEqualTo(b.duplicateKey());
    }

    @Test
    void destinationRequiresLabelAndValidCoords() {
        assertThatThrownBy(() -> FavouriteDestination.create(
                PROFILE, "  ", 38.0, 27.0, PlaceDestinationSource.MAP_PIN, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FavouriteDestination.create(
                PROFILE, "X", 91.0, 27.0, PlaceDestinationSource.MAP_PIN, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void turkishLabelPreserved() {
        FavouriteDestination fav = FavouriteDestination.create(
                PROFILE, "  İzmir Adnan Menderes Havalimanı  ", 38.29, 27.15,
                PlaceDestinationSource.GEOCODING, null, "Gaziemir", NOW);
        assertThat(fav.label()).isEqualTo("İzmir Adnan Menderes Havalimanı");
        assertThat(fav.subtitle()).isEqualTo("Gaziemir");
    }
}
