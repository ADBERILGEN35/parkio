package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PublicExplorePropertiesTest {
    @Test
    void missingAndEmptySourceFamiliesFailClosed() {
        PublicExploreProperties properties = new PublicExploreProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isIzumAllowed()).isFalse();
        assertThat(properties.isIsparkAllowed()).isFalse();
        assertThat(properties.hasAllowedSources()).isFalse();
        assertThat(properties.resolvedSourceKeys()).isEmpty();

        properties.setAllowedSourceFamilies(List.of("", "  "));
        assertThat(properties.isIzumAllowed()).isFalse();
        assertThat(properties.hasAllowedSources()).isFalse();
    }

    @Test
    void reviewedIzumAndIsparkAreAccepted() {
        PublicExploreProperties properties = new PublicExploreProperties();
        properties.setAllowedSourceFamilies(List.of(" izum "));
        assertThat(properties.isIzumAllowed()).isTrue();
        assertThat(properties.isIsparkAllowed()).isFalse();
        assertThat(properties.resolvedSourceKeys()).containsExactly("izmir-izum-otoparklar");

        properties.setAllowedSourceFamilies(List.of("ISPARK"));
        assertThat(properties.isIsparkAllowed()).isTrue();
        assertThat(properties.isIzumAllowed()).isFalse();
        assertThat(properties.resolvedSourceKeys()).containsExactly("istanbul-ispark-parks");

        properties.setAllowedSourceFamilies(List.of("IZUM", "ISPARK", "izum"));
        assertThat(properties.isIzumAllowed()).isTrue();
        assertThat(properties.isIsparkAllowed()).isTrue();
        assertThat(properties.resolvedSourceKeys())
                .containsExactlyInAnyOrder("izmir-izum-otoparklar", "istanbul-ispark-parks");
    }

    @Test
    void unknownAndUnreviewedFamiliesFailClosed() {
        PublicExploreProperties properties = new PublicExploreProperties();
        for (String forbidden : List.of(
                "ANPARK", "KONYA", "KAYSERI", "OSM", "OPENSTREETMAP", "IZELMAN", "unknown", "FAKE_TEST")) {
            assertThatThrownBy(() -> properties.setAllowedSourceFamilies(List.of(forbidden)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Public explore");
        }
    }

    @Test
    void whitespaceAndDuplicatesAreDeterministic() {
        PublicExploreProperties properties = new PublicExploreProperties();
        properties.setAllowedSourceFamilies(List.of(" IZUM ", "IZUM", " ispark ", "ISPARK"));
        assertThat(properties.getAllowedSourceFamilies()).containsExactly("ISPARK", "IZUM");
        assertThat(properties.resolvedSourceKeys()).hasSize(2);
    }
}
