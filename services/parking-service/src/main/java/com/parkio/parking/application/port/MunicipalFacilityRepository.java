package com.parkio.parking.application.port;

import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MunicipalFacilityRepository {
    record Facility(UUID id, String displayName, String operatorName, MunicipalFacilityType facilityType,
                    String addressText, double latitude, double longitude, Integer capacityTotal,
                    boolean paid, boolean nonstop, String sourceLabel, String attribution,
                    long agingAfterSeconds, long staleAfterSeconds) {}
    record Upserted(UUID id, boolean inserted, boolean changed) {}
    Upserted upsert(UUID sourceId, NormalizedMunicipalFacility facility, Instant now);
    List<Facility> nearby(double lat, double lng, int radiusMeters, int limit);
    Optional<Facility> findById(UUID id);
    long count();
}
