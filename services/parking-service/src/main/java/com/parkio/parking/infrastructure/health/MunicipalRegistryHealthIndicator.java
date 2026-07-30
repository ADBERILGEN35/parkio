package com.parkio.parking.infrastructure.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Non-blocking registry diagnostics. Findings are details on an UP component and
 * therefore never fail liveness/readiness by themselves.
 */
@Component("municipalRegistry")
public class MunicipalRegistryHealthIndicator implements HealthIndicator {
    private final JdbcClient jdbc;

    public MunicipalRegistryHealthIndicator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            long aliasesToActive = count("""
                    SELECT count(*) FROM municipal_facility_aliases a
                    JOIN municipal_parking_facilities f ON f.id=a.from_facility_id
                    WHERE f.lifecycle_state <> 'SUPERSEDED' OR f.superseded_by_id <> a.to_facility_id
                    """);
            long orphanLinks = count("""
                    SELECT count(*) FROM municipal_facility_source_links l
                    LEFT JOIN municipal_parking_facilities f ON f.id=l.facility_id
                    WHERE f.id IS NULL
                    """);
            long noActiveSources = count("""
                    SELECT count(*) FROM municipal_parking_facilities f
                    WHERE f.lifecycle_state='ACTIVE' AND NOT EXISTS (
                        SELECT 1 FROM municipal_facility_source_links l
                        WHERE l.facility_id=f.id AND l.active=true)
                    """);
            long unresolvedCandidates = count("""
                    SELECT count(*) FROM municipal_link_candidates
                    WHERE review_state IN ('PENDING','REOPENED')
                    """);
            long inactiveAvailability = count("""
                    SELECT count(*) FROM municipal_occupancy_snapshots o
                    JOIN municipal_parking_facilities f ON f.id=o.facility_id
                    WHERE f.lifecycle_state <> 'ACTIVE'
                    """);
            long duplicateExternalIds = count("""
                    SELECT count(*) FROM (
                        SELECT source_id,external_id FROM municipal_facility_source_links
                        GROUP BY source_id,external_id HAVING count(*) > 1
                    ) duplicates
                    """);
            long invalidPrecedence = count("""
                    SELECT count(*) FROM municipal_facility_field_provenance
                    WHERE field_name='TARIFF_ASSIGNMENT' AND source_age_class <> 'CURRENT'
                    """);
            return Health.up()
                    .withDetail("nonBlocking", true)
                    .withDetail("aliasIntegrityFindings", aliasesToActive)
                    .withDetail("orphanLinks", orphanLinks)
                    .withDetail("facilitiesWithoutActiveSources", noActiveSources)
                    .withDetail("unresolvedCandidates", unresolvedCandidates)
                    .withDetail("invalidPrecedence", invalidPrecedence)
                    .withDetail("availabilityOnInactiveFacilities", inactiveAvailability)
                    .withDetail("duplicateExternalIds", duplicateExternalIds)
                    .build();
        } catch (RuntimeException ex) {
            return Health.up()
                    .withDetail("nonBlocking", true)
                    .withDetail("diagnostics", "unavailable")
                    .build();
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
