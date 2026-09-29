package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.registry.RegistrySourceFamilyPair;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LinkCandidatePairDiscoveryAdapter implements LinkCandidatePairDiscoveryPort {
    private static final UUID EMPTY_SCOPE = new UUID(0, 0);
    private final JdbcClient jdbc;

    public LinkCandidatePairDiscoveryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DiscoveryResult discover(
            RegistrySourceFamilyPair pair,
            RegistrySourceFamilyPair.Family leftFamily,
            double maxDistanceMeters,
            int leftRecordLimit,
            int pairLimit,
            List<UUID> leftFacilityIds,
            List<String> leftExternalIds) {
        Set<String> leftKeys = RegistrySourceFamilyPair.sourceKeys(leftFamily);
        Set<String> rightKeys = RegistrySourceFamilyPair.sourceKeys(pair.other(leftFamily));
        List<UUID> facilityScope = leftFacilityIds == null || leftFacilityIds.isEmpty()
                ? List.of(EMPTY_SCOPE) : leftFacilityIds;
        List<String> externalScope = leftExternalIds == null || leftExternalIds.isEmpty()
                ? List.of("__none__") : leftExternalIds;
        boolean scopedFacilities = leftFacilityIds != null && !leftFacilityIds.isEmpty();
        boolean scopedExternal = leftExternalIds != null && !leftExternalIds.isEmpty();

        String leftCte = """
                WITH left_records AS (
                  SELECT f.id facility_id, l.id link_id, s.source_key, l.external_id, l.raw_record_hash,
                         COALESCE(l.source_name, f.display_name) record_name, f.operator_name,
                         f.facility_type, f.access_classification, f.capacity_total, f.latitude, f.longitude,
                         f.address_text, l.source_metadata_json, f.active facility_active,
                         l.active link_active, f.lifecycle_state, f.location
                  FROM municipal_facility_source_links l
                  JOIN municipal_data_sources s ON s.id=l.source_id
                  JOIN municipal_parking_facilities f ON f.id=l.facility_id
                  WHERE s.source_key IN (:leftKeys)
                    AND l.active=true AND f.active=true AND f.lifecycle_state='ACTIVE'
                    AND (:scopedFacilities=false OR f.id IN (:facilityIds))
                    AND (:scopedExternal=false OR l.external_id IN (:externalIds))
                  ORDER BY f.id, l.id
                  LIMIT :leftLimit
                )
                """;
        var params = jdbc.sql(leftCte + """
                SELECT count(*) FROM left_records
                """)
                .param("leftKeys", leftKeys)
                .param("scopedFacilities", scopedFacilities)
                .param("facilityIds", facilityScope)
                .param("scopedExternal", scopedExternal)
                .param("externalIds", externalScope)
                .param("leftLimit", leftRecordLimit);
        int leftCount = params.query(Integer.class).single();

        List<DiscoveredPair> pairs = jdbc.sql(leftCte + """
                SELECT l.facility_id l_facility_id, l.link_id l_link_id, l.source_key l_source_key,
                       l.external_id l_external_id, l.raw_record_hash l_hash, l.record_name l_name,
                       l.operator_name l_operator, l.facility_type l_type, l.access_classification l_access,
                       l.capacity_total l_capacity, l.latitude l_lat, l.longitude l_lng, l.address_text l_address,
                       l.source_metadata_json l_metadata, l.facility_active l_facility_active,
                       l.link_active l_link_active, l.lifecycle_state l_lifecycle,
                       r.id r_facility_id, rl.id r_link_id, rs.source_key r_source_key,
                       rl.external_id r_external_id, rl.raw_record_hash r_hash,
                       COALESCE(rl.source_name,r.display_name) r_name, r.operator_name r_operator,
                       r.facility_type r_type, r.access_classification r_access, r.capacity_total r_capacity,
                       r.latitude r_lat, r.longitude r_lng, r.address_text r_address,
                       rl.source_metadata_json r_metadata, r.active r_facility_active,
                       rl.active r_link_active, r.lifecycle_state r_lifecycle,
                       ST_Distance(l.location,r.location) distance_meters
                FROM left_records l
                JOIN municipal_parking_facilities r
                  ON ST_DWithin(l.location,r.location,:distance)
                JOIN municipal_facility_source_links rl ON rl.facility_id=r.id AND rl.active=true
                JOIN municipal_data_sources rs ON rs.id=rl.source_id AND rs.source_key IN (:rightKeys)
                WHERE r.active=true AND r.lifecycle_state='ACTIVE'
                ORDER BY l.facility_id, r.id, distance_meters, l.link_id, rl.id
                LIMIT :pairLimit
                """)
                .param("leftKeys", leftKeys)
                .param("rightKeys", rightKeys)
                .param("scopedFacilities", scopedFacilities)
                .param("facilityIds", facilityScope)
                .param("scopedExternal", scopedExternal)
                .param("externalIds", externalScope)
                .param("leftLimit", leftRecordLimit)
                .param("distance", maxDistanceMeters)
                .param("pairLimit", pairLimit)
                .query((rs, row) -> new DiscoveredPair(
                        source(rs, "l_"), source(rs, "r_"), rs.getDouble("distance_meters")))
                .list();
        return new DiscoveryResult(List.copyOf(pairs), leftCount);
    }

    @Override
    public boolean alreadyLinked(DiscoveredPair pair) {
        if (pair.left().facilityId().equals(pair.right().facilityId())) return true;
        return jdbc.sql("""
                SELECT EXISTS (
                  SELECT 1 FROM municipal_facility_aliases
                  WHERE (from_facility_id=:a AND to_facility_id=:b)
                     OR (from_facility_id=:b AND to_facility_id=:a)
                  UNION ALL
                  SELECT 1 FROM municipal_link_candidates
                  WHERE review_state='ACCEPTED'
                    AND ((facility_a_id=:a AND facility_b_id=:b)
                      OR (facility_a_id=:b AND facility_b_id=:a))
                )
                """)
                .param("a", pair.left().facilityId())
                .param("b", pair.right().facilityId())
                .query(Boolean.class)
                .single();
    }

    private static SourceRecord source(java.sql.ResultSet rs, String p) throws java.sql.SQLException {
        Integer capacity = rs.getObject(p + "capacity", Integer.class);
        return new SourceRecord(
                rs.getObject(p + "facility_id", UUID.class),
                rs.getObject(p + "link_id", UUID.class),
                rs.getString(p + "source_key"),
                rs.getString(p + "external_id"),
                rs.getString(p + "hash"),
                rs.getString(p + "name"),
                rs.getString(p + "operator"),
                enumValue(MunicipalFacilityType.class, rs.getString(p + "type"), MunicipalFacilityType.UNKNOWN),
                enumValue(MunicipalAccessClassification.class, rs.getString(p + "access"), MunicipalAccessClassification.UNKNOWN),
                capacity,
                rs.getDouble(p + "lat"),
                rs.getDouble(p + "lng"),
                rs.getString(p + "address"),
                rs.getString(p + "metadata"),
                rs.getBoolean(p + "facility_active"),
                rs.getBoolean(p + "link_active"),
                rs.getString(p + "lifecycle"));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
