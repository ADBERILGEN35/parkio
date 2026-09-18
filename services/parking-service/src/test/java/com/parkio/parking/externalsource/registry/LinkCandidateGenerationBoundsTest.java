package com.parkio.parking.externalsource.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LinkCandidateGenerationBoundsTest {
    @Test
    void appliesDefaultsAndClampsMaxima() {
        assertThat(LinkCandidateGenerationBounds.normalize(null, null, null, null))
                .isEqualTo(new LinkCandidateGenerationBounds(100, 100, 1000, 20));
        assertThat(LinkCandidateGenerationBounds.normalize(999d, 9999, 99999, 999))
                .isEqualTo(new LinkCandidateGenerationBounds(250, 1000, 10000, 20));
    }

    @Test
    void rejectsNonPositiveValues() {
        assertThatThrownBy(() -> LinkCandidateGenerationBounds.normalize(0d, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LinkCandidateGenerationBounds.normalize(null, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
