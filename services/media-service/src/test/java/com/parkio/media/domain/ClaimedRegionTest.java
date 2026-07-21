package com.parkio.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClaimedRegionTest {

    @Test
    void acceptsValidRegionAtMinimumArea() {
        ClaimedRegion region = ClaimedRegion.of(0.1, 0.1, 0.5, 0.5);
        assertThat(region.area()).isGreaterThanOrEqualTo(ClaimedRegion.MIN_AREA);
    }

    @ParameterizedTest
    @CsvSource({
            "NaN,0.1,0.5,0.5",
            "0.1,NaN,0.5,0.5",
            "0.1,0.1,NaN,0.5",
            "0.1,0.1,0.5,NaN",
            "Infinity,0.1,0.5,0.5",
            "0.1,-Infinity,0.5,0.5"
    })
    void rejectsNonFiniteValues(double x, double y, double width, double height) {
        assertThatThrownBy(() -> ClaimedRegion.of(x, y, width, height))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void rejectsNegativeOrigin() {
        assertThatThrownBy(() -> ClaimedRegion.of(-0.01, 0.1, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaimedRegion.of(0.1, -0.01, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroWidthOrHeight() {
        assertThatThrownBy(() -> ClaimedRegion.of(0.1, 0.1, 0.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaimedRegion.of(0.1, 0.1, 0.5, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnyFieldGreaterThanOne() {
        assertThatThrownBy(() -> ClaimedRegion.of(1.01, 0.1, 0.1, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaimedRegion.of(0.1, 0.1, 1.01, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverflowBeyondImageBounds() {
        assertThatThrownBy(() -> ClaimedRegion.of(0.7, 0.1, 0.4, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fit inside");
        assertThatThrownBy(() -> ClaimedRegion.of(0.1, 0.7, 0.5, 0.4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fit inside");
    }

    @Test
    void rejectsAreaBelowMinimum() {
        assertThatThrownBy(() -> ClaimedRegion.of(0.1, 0.1, 0.2, 0.2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
    }
}