package com.parkio.parking.externalsource.osm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OsmCapacityParserTest {
    @Test void parsesAndRejectsInvalidCapacity() {
        assertThat(OsmCapacityParser.parse("120")).isEqualTo(120);
        assertThat(OsmCapacityParser.parse("50 spaces")).isEqualTo(50);
        assertThat(OsmCapacityParser.parse("-1")).isNull();
        assertThat(OsmCapacityParser.parse("abc")).isNull();
        assertThat(OsmCapacityParser.parse(null)).isNull();
    }
}