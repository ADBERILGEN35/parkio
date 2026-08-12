package com.parkio.parking.infrastructure.anpark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Transport DTO for ANPARK {@code /wp-json/anpark/v1/parks} list rows.
 * Not exposed outside adapter code. No occupancy fields exist upstream.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnparkParkingRecordDto(
        @JsonDeserialize(using = AnparkFlexibleIdDeserializer.class) String id,
        String name,
        String type,
        String district,
        @JsonDeserialize(using = AnparkFlexibleDoubleDeserializer.class) Double lat,
        @JsonDeserialize(using = AnparkFlexibleDoubleDeserializer.class) Double lng,
        Integer capacity,
        String schedule,
        String address,
        Boolean active) {}
