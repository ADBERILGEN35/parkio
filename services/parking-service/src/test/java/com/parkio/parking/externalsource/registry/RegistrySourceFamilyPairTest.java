package com.parkio.parking.externalsource.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import org.junit.jupiter.api.Test;

class RegistrySourceFamilyPairTest {
    @Test
    void resolvesEnabledPairCaseInsensitivelyAndCanonicalizesKey() {
        var pair = RegistrySourceFamilyPair.resolve("izum", "OsM");
        assertThat(pair).isEqualTo(RegistrySourceFamilyPair.IZUM_OSM);
        assertThat(pair.key()).isEqualTo("IZUM_OSM");
        assertThat(RegistrySourceFamilyPair.sourceKeys(RegistrySourceFamilyPair.Family.IZUM))
                .containsExactly(MunicipalSourceIdentity.IZUM);
    }

    @Test
    void rejectsDisabledAndSameFamilyPairs() {
        assertThatThrownBy(() -> RegistrySourceFamilyPair.resolve("IZUM", "IZELMAN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> RegistrySourceFamilyPair.resolve("OSM", "OSM"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
