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

        properties.setAllowedSourceFamilies(List.of("", "  "));
        assertThat(properties.isIzumAllowed()).isFalse();
    }

    @Test
    void onlyIzumIsAccepted() {
        PublicExploreProperties properties = new PublicExploreProperties();
        properties.setAllowedSourceFamilies(List.of(" izum "));
        assertThat(properties.isIzumAllowed()).isTrue();

        for (String forbidden : List.of("ISPARK", "ANPARK", "KONYA", "KAYSERI", "OSM", "IZELMAN", "unknown")) {
            assertThatThrownBy(() -> properties.setAllowedSourceFamilies(List.of(forbidden)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only the reviewed IZUM");
        }
    }
}
