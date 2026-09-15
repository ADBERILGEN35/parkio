package com.parkio.parking.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class KayseriParkingClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private KayseriParkingClient client;

    @BeforeEach
    void setUp() {
        MunicipalSourceProperties properties = new MunicipalSourceProperties();
        properties.getKayseri().setMaxRecords(100);
        client = new KayseriParkingClient(RestClient.builder(), mapper, properties);
    }

    @Test
    void flattensFeatureCollectionPreferringLatLonDd() throws Exception {
        JsonNode geojson = mapper.readTree(getClass().getResourceAsStream(
                "/fixtures/municipal/kayseri/sample-geojson.json"));
        JsonNode flat = client.flattenFeatures(geojson);
        assertThat(flat.isArray()).isTrue();
        assertThat(flat).hasSize(5);
        assertThat(flat.get(0).path("CBNO").asInt()).isEqualTo(2723);
        assertThat(flat.get(0).path("ADI").asText()).contains("TACETTİN");
        assertThat(flat.get(0).path("lat_DD").asDouble()).isEqualTo(38.715748);
        assertThat(flat.get(0).path("lon_DD").asDouble()).isEqualTo(35.491699);
    }

    @Test
    void promotesPointGeometryWhenLatLonMissing() throws Exception {
        JsonNode geojson = mapper.readTree("""
                {
                  "type":"FeatureCollection",
                  "features":[{
                    "type":"Feature",
                    "properties":{"CBNO":1,"ADI":"Geo Only"},
                    "geometry":{"type":"Point","coordinates":[35.5,38.7]}
                  }]
                }
                """);
        JsonNode flat = client.flattenFeatures(geojson);
        assertThat(flat.get(0).path("lon_DD").asDouble()).isEqualTo(35.5);
        assertThat(flat.get(0).path("lat_DD").asDouble()).isEqualTo(38.7);
    }

    @Test
    void rejectsNonFeatureCollection() throws Exception {
        JsonNode bad = mapper.readTree("{\"type\":\"Feature\"}");
        assertThatThrownBy(() -> client.flattenFeatures(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("features");
    }
}
