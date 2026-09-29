package com.parkio.parking.infrastructure.ispark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Transport DTO for İSPARK {@code /ispark/Park} list rows. Not exposed outside adapter code.
 * Coordinates may arrive as strings; capacity fields as numbers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IsparkParkingRecordDto(
        Integer parkID,
        String parkName,
        @JsonDeserialize(using = IsparkFlexibleDoubleDeserializer.class) Double lat,
        @JsonDeserialize(using = IsparkFlexibleDoubleDeserializer.class) Double lng,
        Integer capacity,
        Integer emptyCapacity,
        String workHours,
        String parkType,
        Integer freeTime,
        String district,
        Integer isOpen) {}
