package com.parkio.parking.externalsource.osm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class OsmGeoJsonParkingParserTest {
    @Test void parsesFixtureAndRejectsUnsafeRecords() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/municipal/osm/izmir-parking-sample.geojson")) {
            bytes = in.readAllBytes();
        }
        List<OsmParkingFeature> features = new OsmGeoJsonParkingParser(new ObjectMapper()).parse(bytes);
        assertThat(features).hasSize(7);
        assertThat(features.stream().filter(OsmParkingFeature::valid)).hasSize(5);
        assertThat(features.stream().map(OsmParkingFeature::externalId))
                .contains("node/1001", "way/1001", "way/2002", "relation/3003");
        assertThat(features.stream().filter(f -> "outside_izmir_clip".equals(f.rejectReason()))).hasSize(1);
        assertThat(features.stream().filter(f -> "parking_space_not_facility".equals(f.rejectReason()))).hasSize(1);
        assertThat(features.stream().filter(f -> "access_not_publishable".equals(f.rejectReason()))).isEmpty();
        // private is valid parse; importer may reject publication later
        assertThat(features.stream().filter(f -> f.valid() && f.externalId().equals("node/4004"))).hasSize(1);
    }
}