package com.parkio.parking.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.osm.OsmGeoJsonParkingParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OsmImportConfiguration {
    @Bean
    public OsmGeoJsonParkingParser osmGeoJsonParkingParser(ObjectMapper objectMapper) {
        return new OsmGeoJsonParkingParser(objectMapper);
    }
}