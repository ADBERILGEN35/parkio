package com.parkio.parking.infrastructure.kayseri;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Flattened facility row from Kayseri BB open-data GeoJSON properties.
 *
 * <p>Official grain is one facility per feature with stable {@code CBNO}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KayseriParkingRecordDto(
        @JsonProperty("CBNO") @JsonDeserialize(using = KayseriFlexibleIdDeserializer.class) String cbno,
        @JsonProperty("ADI") String adi,
        @JsonProperty("KISA_ADI") String kisaAdi,
        @JsonProperty("lat_DD") Double latDd,
        @JsonProperty("lon_DD") Double lonDd,
        @JsonProperty("ILCE_CBNO") Integer ilceCbno,
        @JsonProperty("MAH_CBNO") Integer mahCbno,
        @JsonProperty("KATEGORI") Integer kategori,
        @JsonProperty("ALTKATEGOR") Integer altkategor,
        @JsonProperty("KAT_ID") Integer katId) {}
