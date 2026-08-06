package com.parkio.parking.domain.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlaceIdentityTest {

    @Test
    void createsValidIdentity() {
        PlaceIdentity identity = PlaceIdentity.osmNominatim("987654");

        assertThat(identity.provider()).isEqualTo(PlaceIdentity.PROVIDER_OSM_NOMINATIM);
        assertThat(identity.providerPlaceId()).isEqualTo("987654");
        assertThat(identity.canonicalKey()).isEqualTo("osm-nominatim:987654");
    }

    @Test
    void trimsProviderAndId() {
        PlaceIdentity identity = PlaceIdentity.of("  osm-nominatim ", " 42 ");
        assertThat(identity.provider()).isEqualTo("osm-nominatim");
        assertThat(identity.providerPlaceId()).isEqualTo("42");
    }

    @Test
    void blankProviderRejected() {
        assertThatThrownBy(() -> PlaceIdentity.of("  ", "42"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void blankProviderPlaceIdRejected() {
        assertThatThrownBy(() -> PlaceIdentity.of("osm-nominatim", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerPlaceId");
    }

    @Test
    void rejectsNonKebabProvider() {
        assertThatThrownBy(() -> PlaceIdentity.of("OSM_NOMINATIM", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kebab-case");
    }

    @Test
    void sameProviderAndIdAreEqual() {
        PlaceIdentity a = PlaceIdentity.osmNominatim("100");
        PlaceIdentity b = PlaceIdentity.of("osm-nominatim", "100");
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a.canonicalKey()).isEqualTo(b.canonicalKey());
    }

    @Test
    void differentProviderSameIdNotEqual() {
        PlaceIdentity nominatim = PlaceIdentity.osmNominatim("100");
        PlaceIdentity other = PlaceIdentity.of("google-places", "100");

        assertThat(nominatim).isNotEqualTo(other);
        assertThat(nominatim.canonicalKey()).isNotEqualTo(other.canonicalKey());
    }

    @Test
    void canonicalKeyIsDeterministic() {
        PlaceIdentity identity = PlaceIdentity.osmNominatim("abc");
        assertThat(identity.canonicalKey()).isEqualTo("osm-nominatim:abc");
        assertThat(identity.canonicalKey()).isEqualTo(identity.canonicalKey());
    }
}
