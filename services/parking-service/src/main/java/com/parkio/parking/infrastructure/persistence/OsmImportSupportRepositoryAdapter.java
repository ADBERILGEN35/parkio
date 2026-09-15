package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.OsmImportSupportRepository;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.osm.ConflationDecision;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OsmImportSupportRepositoryAdapter implements OsmImportSupportRepository {
    private final JdbcClient jdbc;

    public OsmImportSupportRepositoryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> activeExternalIds(UUID sourceId) {
        List<String> ids = jdbc.sql("""
                SELECT external_id FROM municipal_facility_source_links
                WHERE source_id=:sourceId AND active=true
                """).param("sourceId", sourceId).query(String.class).list();
        return new HashSet<>(ids);
    }

    @Override
    public int deactivateMissing(
            UUID sourceId, Set<String> seenExternalIds, Instant now, boolean trustedAuthoritativeEmptySet) {
        // Refuse mass wipe on an empty seen-set unless the caller proved a trustworthy authoritative snapshot.
        if (seenExternalIds.isEmpty() && !trustedAuthoritativeEmptySet) {
            return 0;
        }
        // Deactivate active links not in seen set via NOT IN is awkward for large sets;
        // use temp strategy: mark all unseen by excluding matched ids in Java-sized batches.
        List<String> active = jdbc.sql("""
                SELECT external_id FROM municipal_facility_source_links
                WHERE source_id=:sourceId AND active=true
                """).param("sourceId", sourceId).query(String.class).list();
        int count = 0;
        for (String externalId : active) {
            if (!seenExternalIds.contains(externalId)) {
                count += jdbc.sql("""
                        UPDATE municipal_facility_source_links
                        SET active=false, updated_at=:now
                        WHERE source_id=:sourceId AND external_id=:externalId AND active=true
                        """).param("sourceId", sourceId).param("externalId", externalId)
                        .param("now", Timestamp.from(now)).update();
                jdbc.sql("""
                        UPDATE municipal_parking_facilities f
                        SET active=false, updated_at=:now
                        WHERE f.id = (
                            SELECT facility_id FROM municipal_facility_source_links
                            WHERE source_id=:sourceId AND external_id=:externalId
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM municipal_facility_source_links l
                            WHERE l.facility_id=f.id AND l.active=true
                        )
                        """).param("sourceId", sourceId).param("externalId", externalId)
                        .param("now", Timestamp.from(now)).update();
            }
        }
        return count;
    }

    @Override
    public int reactivate(UUID sourceId, String externalId, Instant now) {
        return jdbc.sql("""
                UPDATE municipal_facility_source_links
                SET active=true, updated_at=:now, last_seen_at=:now
                WHERE source_id=:sourceId AND external_id=:externalId
                """).param("sourceId", sourceId).param("externalId", externalId)
                .param("now", Timestamp.from(now)).update();
    }

    @Override
    public List<MunicipalCandidate> findMunicipalCandidatesNear(double lat, double lng, double radiusMeters) {
        String izum = IzumMunicipalParkingAdapter.SOURCE_KEY;
        return jdbc.sql("""
                SELECT f.id, l.external_id, f.display_name, f.operator_name, f.facility_type,
                       f.access_classification, f.capacity_total, f.latitude, f.longitude
                FROM municipal_parking_facilities f
                JOIN municipal_facility_source_links l ON l.facility_id=f.id AND l.active=true
                JOIN municipal_data_sources s ON s.id=l.source_id AND s.source_key=:izum
                WHERE f.active=true
                  AND ST_DWithin(f.location, ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography, :radius)
                """).param("izum", izum).param("lat", lat).param("lng", lng).param("radius", radiusMeters)
                .query((rs, row) -> new MunicipalCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("external_id"),
                        rs.getString("display_name"),
                        rs.getString("operator_name"),
                        MunicipalFacilityType.valueOf(rs.getString("facility_type")),
                        MunicipalAccessClassification.valueOf(rs.getString("access_classification")),
                        (Integer) rs.getObject("capacity_total"),
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude"))).list();
    }

    @Override
    public Optional<ExistingDecision> findActiveDecision(UUID facilityA, UUID facilityB) {
        return jdbc.sql("""
                SELECT id, facility_a_id, facility_b_id, external_id_a, external_id_b, decision
                FROM municipal_facility_conflation_decisions
                WHERE superseded=false
                  AND ((facility_a_id=:a AND facility_b_id=:b) OR (facility_a_id=:b AND facility_b_id=:a))
                LIMIT 1
                """).param("a", facilityA).param("b", facilityB)
                .query((rs, row) -> new ExistingDecision(
                        rs.getObject("id", UUID.class),
                        rs.getObject("facility_a_id", UUID.class),
                        rs.getObject("facility_b_id", UUID.class),
                        rs.getString("external_id_a"),
                        rs.getString("external_id_b"),
                        ConflationDecision.valueOf(rs.getString("decision")))).optional();
    }

    @Override
    public Optional<ExistingDecision> findActiveDecisionByExternalPair(
            String sourceKeyA, String externalA, String sourceKeyB, String externalB) {
        return jdbc.sql("""
                SELECT id, facility_a_id, facility_b_id, external_id_a, external_id_b, decision
                FROM municipal_facility_conflation_decisions
                WHERE superseded=false
                  AND ((source_key_a=:sa AND external_id_a=:ea AND source_key_b=:sb AND external_id_b=:eb)
                    OR (source_key_a=:sb AND external_id_a=:eb AND source_key_b=:sa AND external_id_b=:ea))
                LIMIT 1
                """).param("sa", sourceKeyA).param("ea", externalA).param("sb", sourceKeyB).param("eb", externalB)
                .query((rs, row) -> new ExistingDecision(
                        rs.getObject("id", UUID.class),
                        rs.getObject("facility_a_id", UUID.class),
                        rs.getObject("facility_b_id", UUID.class),
                        rs.getString("external_id_a"),
                        rs.getString("external_id_b"),
                        ConflationDecision.valueOf(rs.getString("decision")))).optional();
    }

    @Override
    public void insertDecision(
            UUID facilityA, UUID facilityB,
            String sourceKeyA, String sourceKeyB,
            String externalA, String externalB,
            ConflationDecision decision, String reason, String policyVersion,
            String signalJson, Double score, boolean automatic, String actor, Instant now) {
        jdbc.sql("""
                INSERT INTO municipal_facility_conflation_decisions
                    (id, facility_a_id, facility_b_id, source_key_a, source_key_b, external_id_a, external_id_b,
                     decision, decision_reason, policy_version, signal_values_json, total_score,
                     automatic, actor, decided_at, superseded, created_at)
                VALUES (:id,:a,:b,:sa,:sb,:ea,:eb,:decision,:reason,:policy,CAST(:signals AS jsonb),:score,
                        :automatic,:actor,:now,false,:now)
                """).param("id", UUID.randomUUID()).param("a", facilityA).param("b", facilityB)
                .param("sa", sourceKeyA).param("sb", sourceKeyB).param("ea", externalA).param("eb", externalB)
                .param("decision", decision.name()).param("reason", reason).param("policy", policyVersion)
                .param("signals", signalJson).param("score", score).param("automatic", automatic)
                .param("actor", actor).param("now", Timestamp.from(now)).update();
    }

    @Override
    public void reassignOsmLinkToFacility(UUID sourceId, String osmExternalId, UUID targetFacilityId, Instant now) {
        Optional<UUID> oldFacility = jdbc.sql("""
                SELECT facility_id FROM municipal_facility_source_links
                WHERE source_id=:sourceId AND external_id=:externalId
                """).param("sourceId", sourceId).param("externalId", osmExternalId)
                .query(UUID.class).optional();
        jdbc.sql("""
                UPDATE municipal_facility_source_links
                SET facility_id=:facilityId, updated_at=:now, active=true
                WHERE source_id=:sourceId AND external_id=:externalId
                """).param("facilityId", targetFacilityId).param("sourceId", sourceId)
                .param("externalId", osmExternalId).param("now", Timestamp.from(now)).update();
        if (oldFacility.isPresent() && !oldFacility.get().equals(targetFacilityId)) {
            softDeactivateFacility(oldFacility.get(), now);
        }
    }

    @Override
    public void softDeactivateFacility(UUID facilityId, Instant now) {
        jdbc.sql("""
                UPDATE municipal_parking_facilities
                SET active=false, updated_at=:now
                WHERE id=:id
                  AND NOT EXISTS (
                    SELECT 1 FROM municipal_facility_source_links l
                    WHERE l.facility_id=:id AND l.active=true
                  )
                """).param("id", facilityId).param("now", Timestamp.from(now)).update();
    }

    @Override
    public void saveImportRun(
            UUID id, UUID syncRunId, String filename, String sourceUrl, Instant downloadedAt,
            Long fileSize, String sha256, String configVersion, String clipVersion, boolean dryRun,
            ImportRunStats stats, Instant now) {
        jdbc.sql("""
                INSERT INTO municipal_osm_import_runs
                    (id, sync_run_id, input_filename, source_url, downloaded_at, file_size_bytes, sha256,
                     import_config_version, clip_version, dry_run, complete_success,
                     elements_read, extracted, rejected, inserted, updated, unchanged, deactivated, reactivated,
                     conflation_candidates, auto_matched, review_required, rejected_matches, hard_conflicts,
                     quality_report_json, created_at)
                VALUES (:id,:sync,:file,:url,:downloaded,:size,:sha,:config,:clip,:dry,:complete,
                        :elements,:extracted,:rejected,:inserted,:updated,:unchanged,:deactivated,:reactivated,
                        :candidates,:auto,:review,:rejMatches,:hard,CAST(:report AS jsonb),:now)
                """).param("id", id).param("sync", syncRunId).param("file", filename).param("url", sourceUrl)
                .param("downloaded", downloadedAt == null ? null : Timestamp.from(downloadedAt))
                .param("size", fileSize).param("sha", sha256).param("config", configVersion)
                .param("clip", clipVersion).param("dry", dryRun).param("complete", stats.completeSuccess())
                .param("elements", stats.elementsRead()).param("extracted", stats.extracted())
                .param("rejected", stats.rejected()).param("inserted", stats.inserted())
                .param("updated", stats.updated()).param("unchanged", stats.unchanged())
                .param("deactivated", stats.deactivated()).param("reactivated", stats.reactivated())
                .param("candidates", stats.conflationCandidates()).param("auto", stats.autoMatched())
                .param("review", stats.reviewRequired()).param("rejMatches", stats.rejectedMatches())
                .param("hard", stats.hardConflicts()).param("report", stats.qualityReportJson())
                .param("now", Timestamp.from(now)).update();
    }
}