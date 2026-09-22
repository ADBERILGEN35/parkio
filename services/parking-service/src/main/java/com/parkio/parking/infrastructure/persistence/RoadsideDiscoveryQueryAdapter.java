package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.RoadsideDiscoveryQueryPort;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RoadsideDiscoveryQueryAdapter implements RoadsideDiscoveryQueryPort {
    private final JdbcClient jdbc;

    public RoadsideDiscoveryQueryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countNearby(double lat, double lng, int radiusMeters) {
        Long n = jdbc.sql("""
                SELECT count(*) FROM municipal_roadside_segments s
                WHERE s.active=true AND s.publication_status='PUBLISHED'
                  AND s.location IS NOT NULL
                  AND EXISTS (
                    SELECT 1 FROM municipal_roadside_source_links l
                    JOIN municipal_data_sources d ON d.id=l.source_id AND d.active=true
                    WHERE l.segment_id=s.id AND l.active=true
                      AND d.source_key=:sourceKey
                  )
                  AND ST_DWithin(
                    s.location,
                    ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography,
                    :radius)
                """)
                .param("sourceKey", IzelmanSourceKeys.ROADSIDE)
                .param("lat", lat).param("lng", lng).param("radius", radiusMeters)
                .query(Long.class).optional().orElse(0L);
        return n == null ? 0L : n;
    }

    @Override
    public List<RoadsideSegment> nearby(double lat, double lng, int radiusMeters, int limit) {
        return jdbc.sql("""
                SELECT s.id,s.display_name,s.address_or_description,s.latitude,s.longitude,
                       s.capacity_total,s.updated_at
                FROM municipal_roadside_segments s
                WHERE s.active=true AND s.publication_status='PUBLISHED'
                  AND s.location IS NOT NULL
                  AND EXISTS (
                    SELECT 1 FROM municipal_roadside_source_links l
                    JOIN municipal_data_sources d ON d.id=l.source_id AND d.active=true
                    WHERE l.segment_id=s.id AND l.active=true
                      AND d.source_key=:sourceKey
                  )
                  AND ST_DWithin(
                    s.location,
                    ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography,
                    :radius)
                ORDER BY ST_Distance(
                    s.location,
                    ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography)
                LIMIT :limit
                """)
                .param("sourceKey", IzelmanSourceKeys.ROADSIDE)
                .param("lat", lat).param("lng", lng).param("radius", radiusMeters).param("limit", limit)
                .query((rs, row) -> {
                    java.sql.Timestamp updated = rs.getTimestamp("updated_at");
                    return new RoadsideSegment(
                            rs.getObject("id", UUID.class),
                            rs.getString("display_name"),
                            rs.getString("address_or_description"),
                            rs.getDouble("latitude"),
                            rs.getDouble("longitude"),
                            (Integer) rs.getObject("capacity_total"),
                            updated == null ? null : updated.toInstant());
                })
                .list();
    }
}
