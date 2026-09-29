package com.parkio.user.domain.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SavedPlaceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID PROFILE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void homeValidWithoutStoredLabel() {
        SavedPlace home = SavedPlace.create(
                PROFILE, SavedPlaceKind.HOME, null, 41.01, 28.97,
                PlaceDestinationSource.SYSTEM, null, null, NOW);
        assertThat(home.kind()).isEqualTo(SavedPlaceKind.HOME);
        assertThat(home.label()).isNull();
        assertThat(home.displayLabel()).isEqualTo("Home");
    }

    @Test
    void workValidWithOptionalLabel() {
        SavedPlace work = SavedPlace.create(
                PROFILE, SavedPlaceKind.WORK, "Ofis", 41.04, 29.0,
                PlaceDestinationSource.MAP_PIN, null, null, NOW);
        assertThat(work.displayLabel()).isEqualTo("Ofis");
    }

    @Test
    void customRequiresLabel() {
        assertThatThrownBy(() -> SavedPlace.create(
                PROFILE, SavedPlaceKind.CUSTOM, "  ", 41.0, 29.0,
                PlaceDestinationSource.MAP_PIN, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SavedPlace.create(
                PROFILE, SavedPlaceKind.CUSTOM, null, 41.0, 29.0,
                PlaceDestinationSource.MAP_PIN, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customAcceptsTurkishLabel() {
        SavedPlace place = SavedPlace.create(
                PROFILE, SavedPlaceKind.CUSTOM, "  Evim  ", 41.0082, 28.9784,
                PlaceDestinationSource.GEOCODING,
                PlaceIdentity.of("osm-nominatim", "N42"),
                "Kadıköy",
                NOW);
        assertThat(place.label()).isEqualTo("Evim");
        assertThat(place.subtitle()).isEqualTo("Kadıköy");
        assertThat(place.placeIdentityOptional()).isPresent();
        assertThat(place.placeIdentity().canonicalKey()).isEqualTo("osm-nominatim:N42");
    }

    @Test
    void rejectsInvalidCoordinates() {
        assertThatThrownBy(() -> SavedPlace.create(
                PROFILE, SavedPlaceKind.HOME, null, 91.0, 0.0,
                PlaceDestinationSource.SYSTEM, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SavedPlace.create(
                PROFILE, SavedPlaceKind.HOME, null, 0.0, 181.0,
                PlaceDestinationSource.SYSTEM, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SavedPlace.create(
                PROFILE, SavedPlaceKind.HOME, null, Double.NaN, 0.0,
                PlaceDestinationSource.SYSTEM, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownershipFieldsAreImmutable() {
        SavedPlace place = SavedPlace.create(
                PROFILE, SavedPlaceKind.WORK, null, 40.0, 29.0,
                PlaceDestinationSource.MAP_PIN, null, null, NOW);
        assertThat(place.userProfileId()).isEqualTo(PROFILE);
        assertThat(place.id()).isNotNull();
    }
}
