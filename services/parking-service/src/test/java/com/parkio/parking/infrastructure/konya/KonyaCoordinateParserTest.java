package com.parkio.parking.infrastructure.konya;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KonyaCoordinateParserTest {
    private KonyaCoordinateParser parser;

    @BeforeEach
    void setUp() {
        parser = new KonyaCoordinateParser(new ObjectMapper());
    }

    @Test
    void parsesPointLineAndPolygonRepresentations() {
        assertThat(parser.parsePoints("[32.4200373888016, 37.8516916957049]"))
                .containsExactly(new KonyaCoordinateParser.KonyaCoordinatePoint(37.8516916957049, 32.4200373888016));
        assertThat(parser.parsePoints("[[32.48686462640762, 37.8728907124379], [32.487594187259674, 37.87337768241885]]"))
                .hasSize(2);
        assertThat(parser.parsePoints(
                        "[[[32.48814672231674, 37.8729881066916], [32.487803399562836, 37.87337556516502], [32.48814672231674, 37.8729881066916]]]"))
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void malformedAndBlankInputsReturnEmpty() {
        assertThat(parser.parsePoints(null)).isEmpty();
        assertThat(parser.parsePoints("")).isEmpty();
        assertThat(parser.parsePoints("not-json")).isEmpty();
    }
}
