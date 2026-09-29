package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.MunicipalQualityReportQueryPort;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.externalsource.osm.OsmDisplayLabelOutcome;
import com.parkio.parking.externalsource.registry.MunicipalQualityReportPolicy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Aggregate-only quality report queries (DATA-WP-15).
 *
 * <p>Query complexity: every statement is a bounded aggregate. Facility/link counts use
 * {@code idx_municipal_parking_facilities_active} plus the
 * {@code uq_municipal_facility_source_links_source_ext} / facility indexes. İZUM freshness
 * uses {@code DISTINCT ON (facility_id)} over
 * {@code idx_municipal_occupancy_snapshots_facility_fetched}. Import-report lookup is a
 * {@code LIMIT 1} on {@code idx_municipal_source_sync_runs_source_started}. Duplicate
 * guardrails are full {@code GROUP BY ... HAVING} scans over the link and provenance tables,
 * which are small relative to occupancy history.
 *
 * <p>{@code source_metadata_json} is stored as {@code TEXT}. JSON extraction is wrapped in an
 * {@code OFFSET 0} optimisation fence so the cast only sees rows that already passed the
 * object-prefix filter; the ingest writer always serialises OSM link metadata with Jackson.
 */
@Repository
public class MunicipalQualityReportQueryAdapter implements MunicipalQualityReportQueryPort {
    private static final String OSM_LINK_CTE = """
            WITH osm_links AS (
                SELECT f.id AS facility_id,
                       f.display_name AS display_name,
                       CASE WHEN l.source_metadata_json IS NOT NULL
                                 AND left(btrim(l.source_metadata_json), 1) = '{'
                            THEN l.source_metadata_json END AS meta
                FROM municipal_facility_source_links l
                JOIN municipal_data_sources s ON s.id = l.source_id
                JOIN municipal_parking_facilities f ON f.id = l.facility_id
                WHERE l.active = TRUE AND f.active = TRUE AND s.source_key = :osmKey
                OFFSET 0
            )
            """;

    private final JdbcClient jdbc;

    public MunicipalQualityReportQueryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countActiveFacilities() {
        return single(jdbc.sql("""
                SELECT count(*)::bigint
                FROM municipal_parking_facilities
                WHERE active = TRUE
                """).query(Long.class).single());
    }

    @Override
    public long countActiveFacilitiesBySourceKey(String sourceKey) {
        return single(jdbc.sql("""
                SELECT count(DISTINCT f.id)::bigint
                FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id = f.id AND l.active = TRUE
                JOIN municipal_data_sources s ON s.id = l.source_id
                WHERE f.active = TRUE AND s.source_key = :sourceKey
                """).param("sourceKey", sourceKey).query(Long.class).single());
    }

    @Override
    public long countActiveLinksBySourceKey(String sourceKey) {
        return single(jdbc.sql("""
                SELECT count(*)::bigint
                FROM municipal_facility_source_links l
                JOIN municipal_data_sources s ON s.id = l.source_id
                WHERE l.active = TRUE AND s.source_key = :sourceKey
                """).param("sourceKey", sourceKey).query(Long.class).single());
    }

    @Override
    public long countOsmOccupancy() {
        return single(jdbc.sql("""
                SELECT count(*)::bigint
                FROM municipal_occupancy_snapshots o
                JOIN municipal_data_sources s ON s.id = o.source_id
                WHERE s.source_key = :osmKey
                """).param("osmKey", MunicipalSourceIdentity.OSM).query(Long.class).single());
    }

    @Override
    public long countOsmNullAvailability() {
        return single(jdbc.sql("""
                SELECT count(DISTINCT f.id)::bigint
                FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id = f.id AND l.active = TRUE
                JOIN municipal_data_sources s ON s.id = l.source_id
                WHERE f.active = TRUE AND s.source_key = :osmKey
                  AND NOT EXISTS (
                      SELECT 1 FROM municipal_occupancy_snapshots o
                      WHERE o.facility_id = f.id
                        AND o.source_id = s.id
                        AND o.available_spaces IS NOT NULL)
                """).param("osmKey", MunicipalSourceIdentity.OSM).query(Long.class).single());
    }

    @Override
    public List<LabelOutcomeCount> labelOutcomeHistogram() {
        return jdbc.sql(OSM_LINK_CTE + """
                SELECT COALESCE(meta::json ->> 'labelOutcome', 'unknown') AS outcome,
                       count(*)::bigint AS total
                FROM osm_links
                GROUP BY 1
                ORDER BY 1
                """)
                .param("osmKey", MunicipalSourceIdentity.OSM)
                .query((rs, row) -> new LabelOutcomeCount(rs.getString("outcome"), rs.getLong("total")))
                .list();
    }

    @Override
    public long technicalLabelCount() {
        return single(jdbc.sql(OSM_LINK_CTE + """
                SELECT count(DISTINCT facility_id)::bigint
                FROM osm_links
                WHERE meta::json ->> 'labelOutcome' = :legacyOutcome
                   OR display_name ILIKE 'OSM parking %'
                """)
                .param("osmKey", MunicipalSourceIdentity.OSM)
                .param("legacyOutcome", OsmDisplayLabelOutcome.LEGACY_TECHNICAL.metricOutcome())
                .query(Long.class).single());
    }

    @Override
    public List<FieldCoverage> provenanceCoverageBySource(String sourceKey) {
        return jdbc.sql("""
                SELECT p.field_name AS field_name, count(DISTINCT p.facility_id)::bigint AS covered
                FROM municipal_facility_field_provenance p
                JOIN municipal_parking_facilities f ON f.id = p.facility_id
                WHERE f.active = TRUE
                  AND p.source_key = :sourceKey
                  AND p.field_name IN (:fields)
                GROUP BY p.field_name
                """)
                .param("sourceKey", sourceKey)
                .param("fields", MunicipalQualityReportPolicy.PROVENANCE_FIELD_ORDER)
                .query((rs, row) -> new FieldCoverage(rs.getString("field_name"), rs.getLong("covered")))
                .list();
    }

    @Override
    public long staleNameMismatchCount() {
        return single(jdbc.sql(OSM_LINK_CTE + """
                SELECT count(DISTINCT o.facility_id)::bigint
                FROM osm_links o
                WHERE o.meta::json ->> 'labelOutcome' IN (:fallbackOutcomes)
                  AND EXISTS (
                      SELECT 1 FROM municipal_facility_field_provenance p
                      WHERE p.facility_id = o.facility_id
                        AND p.field_name = 'NAME'
                        AND p.source_key = :osmKey)
                """)
                .param("osmKey", MunicipalSourceIdentity.OSM)
                .param("fallbackOutcomes",
                        List.copyOf(MunicipalQualityReportPolicy.FALLBACK_LABEL_OUTCOMES))
                .query(Long.class).single());
    }

    @Override
    public IntegrityCounts integrityCounts() {
        return jdbc.sql("""
                SELECT
                    (SELECT count(*) FROM (
                        SELECT 1 FROM municipal_facility_source_links
                        GROUP BY source_id, external_id HAVING count(*) > 1) dup)::bigint
                        AS duplicate_link_groups,
                    (SELECT count(*) FROM (
                        SELECT 1 FROM municipal_facility_field_provenance
                        GROUP BY facility_id, field_name HAVING count(*) > 1) dup)::bigint
                        AS duplicate_provenance_groups,
                    (SELECT count(*) FROM municipal_link_candidates)::bigint AS link_candidates,
                    (SELECT count(*) FROM municipal_link_candidates
                      WHERE review_state = 'PENDING')::bigint AS pending_link_candidates,
                    (SELECT count(*) FROM municipal_link_review_audit)::bigint AS link_review_decisions,
                    (SELECT count(*) FROM municipal_facility_aliases)::bigint AS facility_aliases,
                    (SELECT count(*) FROM municipal_tariff_plans)::bigint AS tariff_plans,
                    (SELECT count(*) FROM municipal_tariff_assignments
                      WHERE active = TRUE)::bigint AS active_tariff_assignments,
                    (SELECT count(DISTINCT f.id)
                       FROM municipal_parking_facilities f
                       JOIN municipal_facility_source_links l
                         ON l.facility_id = f.id AND l.active = TRUE
                       JOIN municipal_data_sources s ON s.id = l.source_id
                      WHERE f.active = TRUE
                        AND s.source_key IN (:izelmanFacilityKeys))::bigint AS izelman_facilities,
                    (SELECT count(*)
                       FROM municipal_occupancy_snapshots o
                       JOIN municipal_data_sources s ON s.id = o.source_id
                      WHERE s.source_key = :osmKey)::bigint AS osm_occupancy
                """)
                .param("izelmanFacilityKeys", List.of(
                        IzelmanSourceKeys.OPEN, IzelmanSourceKeys.CLOSED, IzelmanSourceKeys.BARRIER))
                .param("osmKey", MunicipalSourceIdentity.OSM)
                .query((rs, row) -> new IntegrityCounts(
                        rs.getLong("duplicate_link_groups"),
                        rs.getLong("duplicate_provenance_groups"),
                        rs.getLong("link_candidates"),
                        rs.getLong("pending_link_candidates"),
                        rs.getLong("link_review_decisions"),
                        rs.getLong("facility_aliases"),
                        rs.getLong("tariff_plans"),
                        rs.getLong("active_tariff_assignments"),
                        rs.getLong("izelman_facilities"),
                        rs.getLong("osm_occupancy")))
                .single();
    }

    @Override
    public Optional<String> latestOsmImportQualityReportJson() {
        return jdbc.sql("""
                SELECT r.quality_report_json
                FROM municipal_osm_import_runs r
                JOIN municipal_source_sync_runs sr ON sr.id = r.sync_run_id
                JOIN municipal_data_sources s ON s.id = sr.source_id
                WHERE s.source_key = :osmKey AND r.quality_report_json IS NOT NULL
                ORDER BY sr.started_at DESC, r.created_at DESC
                LIMIT 1
                """)
                .param("osmKey", MunicipalSourceIdentity.OSM)
                .query((rs, row) -> rs.getString("quality_report_json"))
                .optional();
    }

    @Override
    public IzumFreshness countIzumFreshnessBuckets(long agingSeconds, long staleSeconds, Instant now) {
        Instant agingCutoff = now.minusSeconds(Math.max(0, agingSeconds));
        Instant staleCutoff = now.minusSeconds(Math.max(0, staleSeconds));
        return jdbc.sql("""
                WITH latest AS (
                    SELECT DISTINCT ON (o.facility_id)
                           o.facility_id, o.fetched_at, o.available_spaces
                    FROM municipal_occupancy_snapshots o
                    JOIN municipal_data_sources s ON s.id = o.source_id
                    JOIN municipal_parking_facilities f ON f.id = o.facility_id AND f.active = TRUE
                    WHERE s.source_key = :izumKey
                    ORDER BY o.facility_id, o.fetched_at DESC
                )
                SELECT
                    count(*) FILTER (WHERE fetched_at >= :agingCutoff)::bigint AS live,
                    count(*) FILTER (WHERE fetched_at < :agingCutoff
                                       AND fetched_at >= :staleCutoff)::bigint AS aging,
                    count(*) FILTER (WHERE fetched_at < :staleCutoff)::bigint AS stale,
                    count(*) FILTER (WHERE fetched_at >= :staleCutoff
                                       AND available_spaces IS NOT NULL)::bigint AS availability_exposed,
                    count(*)::bigint AS total
                FROM latest
                """)
                .param("izumKey", MunicipalSourceIdentity.IZUM)
                .param("agingCutoff", Timestamp.from(agingCutoff))
                .param("staleCutoff", Timestamp.from(staleCutoff))
                .query((rs, row) -> new IzumFreshness(
                        rs.getLong("live"),
                        rs.getLong("aging"),
                        rs.getLong("stale"),
                        rs.getLong("availability_exposed"),
                        rs.getLong("total")))
                .single();
    }

    @Override
    public List<com.parkio.parking.application.port.MunicipalDistrictFacilityProjection>
            listActiveFacilityProjections(
                    int maxFacilities, long agingSeconds, long staleSeconds, Instant now) {
        Instant staleCutoff = now.minusSeconds(Math.max(0, staleSeconds));
        int limit = Math.max(1, maxFacilities) + 1;
        String realName = OsmDisplayLabelOutcome.REAL_NAME_SELECTED.metricOutcome();
        String localized = OsmDisplayLabelOutcome.LOCALIZED_NAME_SELECTED.metricOutcome();
        String neutral = OsmDisplayLabelOutcome.NEUTRAL_FALLBACK.metricOutcome();
        return jdbc.sql("""
                WITH osm AS (
                    SELECT DISTINCT ON (l.facility_id)
                           l.facility_id,
                           CASE
                             WHEN left(btrim(l.source_metadata_json), 1) = '{'
                               THEN (l.source_metadata_json::json ->> 'labelOutcome')
                             ELSE NULL
                           END AS label_outcome
                    FROM municipal_facility_source_links l
                    JOIN municipal_data_sources d ON d.id = l.source_id
                    WHERE l.active = TRUE AND d.source_key = :osmKey
                    ORDER BY l.facility_id
                ),
                izum AS (
                    SELECT DISTINCT l.facility_id
                    FROM municipal_facility_source_links l
                    JOIN municipal_data_sources d ON d.id = l.source_id
                    WHERE l.active = TRUE AND d.source_key = :izumKey
                ),
                izum_latest AS (
                    SELECT DISTINCT ON (o.facility_id)
                           o.facility_id, o.fetched_at, o.available_spaces
                    FROM municipal_occupancy_snapshots o
                    JOIN municipal_data_sources d ON d.id = o.source_id
                    WHERE d.source_key = :izumKey
                    ORDER BY o.facility_id, o.fetched_at DESC
                ),
                prov AS (
                    SELECT DISTINCT p.facility_id
                    FROM municipal_facility_field_provenance p
                    WHERE p.field_name IN (:fields)
                )
                SELECT f.id,
                       f.latitude,
                       f.longitude,
                       (osm.facility_id IS NOT NULL) AS osm_linked,
                       (izum.facility_id IS NOT NULL) AS izum_linked,
                       (izum_latest.fetched_at >= :staleCutoff
                          AND izum_latest.available_spaces IS NOT NULL) AS izum_exposed,
                       (osm.label_outcome IN (:realName, :localized)) AS osm_real_name,
                       (osm.label_outcome = :neutral) AS osm_neutral,
                       (prov.facility_id IS NOT NULL) AS provenance_covered
                FROM municipal_parking_facilities f
                LEFT JOIN osm ON osm.facility_id = f.id
                LEFT JOIN izum ON izum.facility_id = f.id
                LEFT JOIN izum_latest ON izum_latest.facility_id = f.id
                LEFT JOIN prov ON prov.facility_id = f.id
                WHERE f.active = TRUE
                ORDER BY f.id
                LIMIT :limit
                """)
                .param("osmKey", MunicipalSourceIdentity.OSM)
                .param("izumKey", MunicipalSourceIdentity.IZUM)
                .param("fields", MunicipalQualityReportPolicy.PROVENANCE_FIELD_ORDER)
                .param("staleCutoff", Timestamp.from(staleCutoff))
                .param("realName", realName)
                .param("localized", localized)
                .param("neutral", neutral)
                .param("limit", limit)
                .query((rs, row) -> new com.parkio.parking.application.port.MunicipalDistrictFacilityProjection(
                        (java.util.UUID) rs.getObject("id"),
                        (Double) rs.getObject("latitude"),
                        (Double) rs.getObject("longitude"),
                        rs.getBoolean("osm_linked"),
                        rs.getBoolean("izum_linked"),
                        rs.getBoolean("izum_exposed"),
                        rs.getBoolean("osm_real_name"),
                        rs.getBoolean("osm_neutral"),
                        rs.getBoolean("provenance_covered")))
                .list();
    }

    private static long single(Long value) {
        return value == null ? 0L : value;
    }
}
