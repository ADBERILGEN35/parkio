package com.parkio.parking.presentation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parking/roadside")
@ConditionalOnProperty(name = {
        "parkio.municipal.enabled", "parkio.municipal.izelman.enabled",
        "parkio.municipal.izelman.roadside-publication-enabled"
}, havingValue = "true")
public class RoadsideParkingController {
    private final JdbcClient jdbc;

    public RoadsideParkingController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/nearby")
    public List<RoadsideView> nearby(
            @RequestParam double lat, @RequestParam double lng,
            @RequestParam(defaultValue = "1000") int radiusMeters,
            @RequestParam(defaultValue = "20") int limit) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lng) || lng < -180 || lng > 180
                || radiusMeters <= 0 || radiusMeters > 5000 || limit <= 0 || limit > 50) {
            throw new IllegalArgumentException("invalid roadside nearby query");
        }
        return jdbc.sql("SELECT s.id,s.display_name,s.district,s.neighborhood,s.address_or_description,"
                        + "s.latitude,s.longitude,s.capacity_total,s.source_age_classification,s.updated_at "
                        + "FROM municipal_roadside_segments s "
                        + "WHERE s.active=true AND s.publication_status='PUBLISHED' "
                        + "AND EXISTS ("
                        + "  SELECT 1 FROM municipal_roadside_source_links l "
                        + "  JOIN municipal_data_sources d ON d.id=l.source_id AND d.active=true "
                        + "  WHERE l.segment_id=s.id AND l.active=true "
                        + "    AND d.source_key='izelman-roadside-parking'"
                        + ") "
                        + "AND s.location IS NOT NULL AND ST_DWithin(s.location, "
                        + "ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography, :radius) "
                        + "ORDER BY ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography) "
                        + "LIMIT :limit")
                .param("lat", lat).param("lng", lng).param("radius", radiusMeters).param("limit", limit)
                .query((rs, row) -> new RoadsideView(
                        rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("district"),
                        rs.getString("neighborhood"), rs.getString("address_or_description"),
                        rs.getDouble("latitude"), rs.getDouble("longitude"),
                        (Integer) rs.getObject("capacity_total"), null, rs.getString("source_age_classification"),
                        "Izmir Metropolitan Municipality / IZELMAN A.S.", true,
                        rs.getObject("updated_at", Instant.class))).list();
    }

    public record RoadsideView(
            UUID id, String displayName, String district, String neighborhood, String address,
            double latitude, double longitude, Integer capacityTotal, Integer availableSpaces,
            String sourceAgeClassification, String attribution, boolean legalStatusUnknown, Instant updatedAt) {}
}