package com.parkio.parking.infrastructure.izum;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IzumParkingRecordDto(
        String ufid,
        String name,
        String provider,
        String type,
        String status,
        Double lat,
        Double lng,
        Occupancy occupancy,
        JsonNode openingHours,
        Boolean isPaid,
        Boolean nonstop,
        String address,
        JsonNode accessories,
        JsonNode poi,
        JsonNode payment,
        JsonNode accessibility,
        JsonNode entrances,
        JsonNode exits) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Occupancy(Total total, Total disabled) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Total(Integer free, Integer occupied) {}
}
