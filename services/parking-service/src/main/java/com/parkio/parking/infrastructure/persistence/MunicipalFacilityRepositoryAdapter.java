package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MunicipalFacilityRepositoryAdapter implements MunicipalFacilityRepository {
    private final JdbcClient jdbc;

    public MunicipalFacilityRepositoryAdapter(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Upserted upsert(UUID sourceId, NormalizedMunicipalFacility value, Instant now) {
        record Existing(UUID id, String hash) {}
        Optional<Existing> existing = jdbc.sql("""
                SELECT f.id, l.raw_record_hash
                FROM municipal_facility_source_links l
                JOIN municipal_parking_facilities f ON f.id=l.facility_id
                WHERE l.source_id=:sourceId AND l.external_id=:externalId
                """).param("sourceId", sourceId).param("externalId", value.externalId())
                .query((rs, row) -> new Existing(rs.getObject(1, UUID.class), rs.getString(2))).optional();
        if (existing.isPresent()) {
            UUID id = existing.get().id();
            jdbc.sql("""
                    UPDATE municipal_parking_facilities
                    SET operator_name=:operator, facility_type=:type, display_name=:name,
                        address_text=:address, latitude=:lat, longitude=:lng,
                        capacity_total=:capacity, access_classification=:access,
                        active=true, updated_at=:now, version=version+1
                    WHERE id=:id
                    """).param("operator", value.operatorName()).param("type", value.facilityType().name())
                    .param("name", value.displayName()).param("address", value.addressText())
                    .param("lat", value.latitude()).param("lng", value.longitude())
                    .param("capacity", value.capacityTotal()).param("access", value.accessClassification().name()).param("now", Timestamp.from(now)).param("id", id).update();
            return new Upserted(id, false, !value.rawRecordHash().equals(existing.get().hash()));
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO municipal_parking_facilities
                    (id, operator_name, facility_type, display_name, address_text, latitude, longitude,
                     capacity_total, opening_hours_json, is_paid, nonstop, active, access_classification, created_at, updated_at, version)
                VALUES (:id,:operator,:type,:name,:address,:lat,:lng,:capacity,NULL,false,false,true,:access,:now,:now,0)
                """).param("id", id).param("operator", value.operatorName()).param("type", value.facilityType().name())
                .param("name", value.displayName()).param("address", value.addressText())
                .param("lat", value.latitude()).param("lng", value.longitude())
                .param("capacity", value.capacityTotal()).param("access", value.accessClassification().name()).param("now", Timestamp.from(now)).update();
        return new Upserted(id, true, true);
    }

    @Override
    public List<Facility> nearby(double lat, double lng, int radiusMeters, int limit) {
        return jdbc.sql("""
                SELECT f.id,f.display_name,f.operator_name,f.facility_type,f.address_text,
                       f.latitude,f.longitude,f.capacity_total,f.is_paid,f.nonstop,
                       s.publisher,s.attribution_text,s.aging_after_seconds,s.stale_after_seconds
                FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id=f.id AND l.active=true
                JOIN municipal_data_sources s ON s.id=l.source_id AND s.active=true
                WHERE f.active=true
                  AND ST_DWithin(f.location, ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography, :radius)
                ORDER BY ST_Distance(f.location, ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography)
                LIMIT :limit
                """).param("lat", lat).param("lng", lng).param("radius", radiusMeters).param("limit", limit)
                .query(this::map).list();
    }

    @Override
    public Optional<Facility> findById(UUID id) {
        return jdbc.sql("""
                SELECT f.id,f.display_name,f.operator_name,f.facility_type,f.address_text,
                       f.latitude,f.longitude,f.capacity_total,f.is_paid,f.nonstop,
                       s.publisher,s.attribution_text,s.aging_after_seconds,s.stale_after_seconds
                FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id=f.id AND l.active=true
                JOIN municipal_data_sources s ON s.id=l.source_id AND s.active=true
                WHERE f.id=:id AND f.active=true
                """).param("id", id).query(this::map).optional();
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT count(*) FROM municipal_parking_facilities").query(Long.class).single();
    }

    private Facility map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Facility(rs.getObject("id", UUID.class), rs.getString("display_name"),
                rs.getString("operator_name"), MunicipalFacilityType.valueOf(rs.getString("facility_type")),
                rs.getString("address_text"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                (Integer) rs.getObject("capacity_total"), rs.getBoolean("is_paid"), rs.getBoolean("nonstop"),
                rs.getString("publisher"), rs.getString("attribution_text"),
                rs.getLong("aging_after_seconds"), rs.getLong("stale_after_seconds"));
    }
}
