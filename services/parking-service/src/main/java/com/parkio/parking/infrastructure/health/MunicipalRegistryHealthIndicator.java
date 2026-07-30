package com.parkio.parking.infrastructure.health;

import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Non-blocking diagnostics: registry findings never lower application health. */
@Component("municipalRegistry")
public class MunicipalRegistryHealthIndicator implements HealthIndicator {
    private final JdbcClient jdbc;
    private final LinkCandidateGenerationRunPort generationRuns;

    public MunicipalRegistryHealthIndicator(
            JdbcClient jdbc, LinkCandidateGenerationRunPort generationRuns) {
        this.jdbc = jdbc;
        this.generationRuns = generationRuns;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up().withDetail("nonBlocking", true);
        try {
            health.withDetail("aliasesToActive", count("""
                    SELECT count(*) FROM municipal_facility_aliases a
                    JOIN municipal_parking_facilities f ON f.id=a.from_facility_id
                    WHERE f.lifecycle_state<>'SUPERSEDED' OR f.superseded_by_id<>a.to_facility_id
                    """));
            health.withDetail("orphanLinks", count("""
                    SELECT count(*) FROM municipal_facility_source_links l
                    LEFT JOIN municipal_parking_facilities f ON f.id=l.facility_id
                    WHERE f.id IS NULL
                    """));
            health.withDetail("unresolvedCandidates", count("""
                    SELECT count(*) FROM municipal_link_candidates
                    WHERE review_state IN ('PENDING','REOPENED')
                    """));
            health.withDetail("inactiveAvailability", count("""
                    SELECT count(*) FROM municipal_occupancy_snapshots o
                    JOIN municipal_parking_facilities f ON f.id=o.facility_id
                    WHERE f.lifecycle_state<>'ACTIVE'
                    """));
            health.withDetail("duplicateExternalIds", count("""
                    SELECT count(*) FROM (
                      SELECT source_id,external_id FROM municipal_facility_source_links
                      GROUP BY source_id,external_id HAVING count(*)>1
                    ) duplicates
                    """));
            health.withDetail("invalidPrecedence", count("""
                    SELECT count(*) FROM municipal_facility_field_provenance
                    WHERE field_name='TARIFF_ASSIGNMENT' AND source_age_class<'CURRENT'
                    """));
            int active = generationRuns.countActiveRunning();
            health.withDetail("activeGenerationRuns", active);
            generationRuns.findLatestCompleted().ifPresent(last -> {
                health.withDetail("lastGenerationStatus", last.status());
                health.withDetail("lastGenerationCompletedAt", last.completedAt());
            });
            health.withDetail(
                    "staleRunningGenerationRuns",
                    generationRuns.countStaleRunning(Instant.now().minus(30, ChronoUnit.MINUTES)));
        } catch (RuntimeException unavailable) {
            health.withDetail("diagnostics", "unavailable");
        }
        return health.build();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
