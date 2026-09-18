package com.parkio.parking.infrastructure.konya;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One CKAN datastore row (bay/peron) from Konya open-data feed. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KonyaParkingRecordDto(
        @JsonProperty("_id") Long rowId,
        String bolgeadi,
        String bolgeadresi,
        Integer bolgekapasite,
        String peronadi,
        String peronadres,
        Integer peronkapasite,
        String peronkoordinat,
        Integer peronacilissaati,
        Integer peronkapanissaati) {}
