package com.parkio.parking.externalsource.osm;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import org.junit.jupiter.api.Test;

class OsmAccessMapperTest {
    @Test void mapsCommonAccessTagsConservatively() {
        assertThat(OsmAccessMapper.map("yes")).isEqualTo(MunicipalAccessClassification.PUBLIC);
        assertThat(OsmAccessMapper.map("customers")).isEqualTo(MunicipalAccessClassification.CUSTOMERS);
        assertThat(OsmAccessMapper.map("private")).isEqualTo(MunicipalAccessClassification.PRIVATE);
        assertThat(OsmAccessMapper.map(null)).isEqualTo(MunicipalAccessClassification.UNKNOWN);
        assertThat(OsmAccessMapper.map("weird")).isEqualTo(MunicipalAccessClassification.UNKNOWN);
        assertThat(OsmAccessMapper.publishable(MunicipalAccessClassification.UNKNOWN)).isTrue();
        assertThat(OsmAccessMapper.publishable(MunicipalAccessClassification.PRIVATE)).isFalse();
    }
}