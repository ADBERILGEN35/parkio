package com.parkio.parking.application;

import com.parkio.parking.infrastructure.config.RegistryProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistryPublicationService {
    public record Enrichment(
            List<String> contributingSourceKeys,
            Map<String, String> selectedFieldProvenanceSummary,
            String registryConfidenceOrReviewStatus) {
        public static Enrichment hidden() {
            return new Enrichment(null, null, null);
        }
    }

    private final RegistryProperties properties;
    private final JdbcClient jdbc;

    public RegistryPublicationService(RegistryProperties properties, JdbcClient jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Enrichment forFacility(UUID facilityId) {
        if (!properties.isProvenancePublicationEnabled()) {
            return Enrichment.hidden();
        }
        List<String> keys = jdbc.sql("""
                SELECT DISTINCT source.source_key
                FROM municipal_facility_source_links link
                JOIN municipal_data_sources source ON source.id=link.source_id
                WHERE link.facility_id=:facility AND link.active=true
                  AND source.active=true AND source.production_approved=true
                ORDER BY source.source_key
                LIMIT 16
                """).param("facility", facilityId).query(String.class).list();
        Map<String, String> summary = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT field_name,source_key,confidence_or_review_state
                FROM municipal_facility_field_provenance
                WHERE facility_id=:facility
                ORDER BY field_name
                LIMIT 11
                """).param("facility", facilityId)
                .query((rs, rowNum) -> Map.entry(
                        rs.getString("field_name"),
                        rs.getString("source_key") + ":" + rs.getString("confidence_or_review_state")))
                .list()
                .forEach(entry -> summary.put(entry.getKey(), entry.getValue()));
        String status = summary.isEmpty() ? "UNASSESSED"
                : summary.values().stream().anyMatch(value -> value.endsWith(":REVIEW_REQUIRED"))
                        ? "REVIEW_REQUIRED" : "PROVENANCE_RECORDED";
        return new Enrichment(List.copyOf(keys), Map.copyOf(summary), status);
    }
}
