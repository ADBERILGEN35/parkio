package com.parkio.parking.externalsource.osm;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import org.junit.jupiter.api.Test;

class FieldPrecedencePolicyTest {
    @Test
    void restrictiveAccessWins() {
        assertThat(FieldPrecedencePolicy.preferAccess(
                        MunicipalAccessClassification.PUBLIC, MunicipalAccessClassification.RESTRICTED))
                .isEqualTo(MunicipalAccessClassification.RESTRICTED);
    }

    @Test
    void municipalNamePreferredOverOsm() {
        assertThat(FieldPrecedencePolicy.preferName("IZELMAN Lot", "OSM Lot")).isEqualTo("IZELMAN Lot");
        assertThat(FieldPrecedencePolicy.preferCapacity(120, 90)).isEqualTo(120);
    }
}