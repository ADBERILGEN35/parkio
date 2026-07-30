package com.parkio.parking.externalsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import java.time.Instant;
import java.util.List;

public interface MunicipalParkingSourceAdapter {
    String sourceKey();
    JsonNode fetch();
    SchemaFingerprint validateContract(JsonNode payload);
    List<NormalizedMunicipalFacility> normalizeFacilities(JsonNode payload, Instant fetchedAt);
    List<NormalizedMunicipalOccupancy> normalizeOccupancy(JsonNode payload, Instant fetchedAt);
}
