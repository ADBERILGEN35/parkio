package com.parkio.parking.application.port;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MunicipalFacilityRepository {
    record Facility(UUID id, String displayName, String operatorName, MunicipalFacilityType facilityType,
                    String addressText, double latitude, double longitude, Integer capacityTotal,
                    boolean paid, boolean nonstop, String sourceLabel, String attribution,
                    long agingAfterSeconds, long staleAfterSeconds,
                    String primarySourceKey, Set<String> linkedSourceKeys,
                    MunicipalAccessClassification accessClassification,
                    String isparkSourceMetadataJson) {
        /**
         * Callers that do not load İSPARK link metadata. Publication treats a missing
         * {@code isOpen} value as unknown, not open.
         */
        public Facility(
                UUID id, String displayName, String operatorName, MunicipalFacilityType facilityType,
                String addressText, double latitude, double longitude, Integer capacityTotal,
                boolean paid, boolean nonstop, String sourceLabel, String attribution,
                long agingAfterSeconds, long staleAfterSeconds,
                String primarySourceKey, Set<String> linkedSourceKeys,
                MunicipalAccessClassification accessClassification) {
            this(id, displayName, operatorName, facilityType, addressText, latitude, longitude,
                    capacityTotal, paid, nonstop, sourceLabel, attribution, agingAfterSeconds,
                    staleAfterSeconds, primarySourceKey, linkedSourceKeys, accessClassification, null);
        }
    }
    record Upserted(UUID id, boolean inserted, boolean changed) {}
    Upserted upsert(UUID sourceId, NormalizedMunicipalFacility facility, Instant now);
    List<Facility> nearby(double lat, double lng, int radiusMeters, int limit);
    Optional<Facility> findById(UUID id);

    /**
     * Anonymous Public Explore nearby rows for an already-validated reviewed source-key set.
     * Global limit ceiling remains enforced in SQL ({@code LEAST(:limit, 6)}).
     */
    List<Facility> publicExploreNearby(
            double lat, double lng, int radiusMeters, int limit, Set<String> allowedSourceKeys);

    /** Count of public-explore facilities in the same bounded scope across allowed sources. */
    long countPublicExploreNearby(
            double lat, double lng, int radiusMeters, Set<String> allowedSourceKeys);

    Optional<Facility> findPublicExploreById(
            UUID id, double lat, double lng, int radiusMeters, Set<String> allowedSourceKeys);

    long count();
}
