package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.RegistryPersistencePort;
import com.parkio.parking.externalsource.registry.FieldProvenanceSelection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class RegistryPersistenceAdapter implements RegistryPersistencePort {
    private final JdbcClient jdbc;

    public RegistryPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Candidate> createCandidateIfAbsent(CandidateDraft draft) {
        UUID id = UUID.randomUUID();
        return jdbc.sql("""
                INSERT INTO municipal_link_candidates (
                    id,facility_a_id,facility_b_id,source_key_a,external_id_a,source_key_b,external_id_b,
                    source_family_pair,evidence_signals_json,score_components_json,total_score,hard_conflicts,
                    generated_at,source_version_a,source_version_b,review_state,algorithm_version,version,
                    created_at,updated_at)
                VALUES (
                    :id,:facilityA,:facilityB,:sourceA,:externalA,:sourceB,:externalB,
                    :familyPair,CAST(:evidence AS jsonb),CAST(:scores AS jsonb),:total,CAST(:conflicts AS jsonb),
                    :generated,:versionA,:versionB,'PENDING',:algorithm,0,:generated,:generated)
                ON CONFLICT (
                    source_key_a,external_id_a,source_key_b,external_id_b,
                    source_version_a,source_version_b,algorithm_version) DO NOTHING
                RETURNING *
                """)
                .param("id", id)
                .param("facilityA", draft.facilityAId())
                .param("facilityB", draft.facilityBId())
                .param("sourceA", draft.sourceKeyA())
                .param("externalA", draft.externalIdA())
                .param("sourceB", draft.sourceKeyB())
                .param("externalB", draft.externalIdB())
                .param("familyPair", draft.sourceFamilyPair())
                .param("evidence", draft.evidenceJson())
                .param("scores", draft.scoreJson())
                .param("total", draft.totalScore())
                .param("conflicts", draft.hardConflictsJson())
                .param("generated", Timestamp.from(draft.generatedAt()))
                .param("versionA", draft.sourceVersionA())
                .param("versionB", draft.sourceVersionB())
                .param("algorithm", draft.algorithmVersion())
                .query(RegistryPersistenceAdapter::mapCandidate)
                .optional();
    }

    @Override
    public CandidatePage findByState(String reviewState, int page, int size) {
        long total = jdbc.sql("""
                SELECT count(*) FROM municipal_link_candidates WHERE review_state=:state
                """).param("state", reviewState).query(Long.class).single();
        List<Candidate> content = jdbc.sql("""
                SELECT * FROM municipal_link_candidates
                WHERE review_state=:state
                ORDER BY generated_at,id
                LIMIT :limit OFFSET :offset
                """)
                .param("state", reviewState)
                .param("limit", size)
                .param("offset", page * size)
                .query(RegistryPersistenceAdapter::mapCandidate)
                .list();
        return new CandidatePage(content, page, size, total);
    }

    @Override
    public Optional<Candidate> findCandidate(UUID id) {
        return jdbc.sql("SELECT * FROM municipal_link_candidates WHERE id=:id")
                .param("id", id)
                .query(RegistryPersistenceAdapter::mapCandidate)
                .optional();
    }

    @Override
    public Candidate review(
            UUID candidateId,
            long expectedVersion,
            String newState,
            String reviewer,
            String reason,
            UUID chosenFacilityId,
            Instant decisionTimestamp) {
        Candidate previous = findCandidate(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("candidate not found"));
        int changed = jdbc.sql("""
                UPDATE municipal_link_candidates
                SET review_state=:state,reviewed_by=:reviewer,decision_ts=:decisionTs,
                    rejection_reason=:reason,chosen_facility_id=:chosen,updated_at=:decisionTs,
                    version=version+1
                WHERE id=:id AND version=:expectedVersion
                """)
                .param("state", newState)
                .param("reviewer", reviewer)
                .param("decisionTs", Timestamp.from(decisionTimestamp))
                .param("reason", reason)
                .param("chosen", chosenFacilityId)
                .param("id", candidateId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (changed != 1) {
            throw new ObjectOptimisticLockingFailureException("MunicipalLinkCandidate", candidateId);
        }
        Candidate updated = findCandidate(candidateId).orElseThrow();
        jdbc.sql("""
                INSERT INTO municipal_link_review_audit (
                    id,candidate_id,previous_state,new_state,reviewer,decision_reason,
                    chosen_facility_id,candidate_version,decision_ts)
                VALUES (:id,:candidate,:previous,:next,:reviewer,:reason,:chosen,:version,:decisionTs)
                """)
                .param("id", UUID.randomUUID())
                .param("candidate", candidateId)
                .param("previous", previous.reviewState())
                .param("next", newState)
                .param("reviewer", reviewer)
                .param("reason", reason)
                .param("chosen", chosenFacilityId)
                .param("version", updated.version())
                .param("decisionTs", Timestamp.from(decisionTimestamp))
                .update();
        return updated;
    }

    @Override
    public void attachSourceLinksAndSupersede(
            Candidate candidate, UUID chosenFacilityId, String reviewer, Instant now) {
        moveLink(candidate.sourceKeyA(), candidate.externalIdA(), chosenFacilityId, now);
        moveLink(candidate.sourceKeyB(), candidate.externalIdB(), chosenFacilityId, now);
        UUID superseded = chosenFacilityId.equals(candidate.facilityAId())
                ? candidate.facilityBId() : candidate.facilityAId();
        if (superseded != null && !superseded.equals(chosenFacilityId)) {
            jdbc.sql("""
                    UPDATE municipal_parking_facilities
                    SET lifecycle_state='SUPERSEDED',superseded_by_id=:chosen,active=false,
                        updated_at=:now,version=version+1
                    WHERE id=:superseded
                    """)
                    .param("chosen", chosenFacilityId)
                    .param("now", Timestamp.from(now))
                    .param("superseded", superseded)
                    .update();
            jdbc.sql("""
                    INSERT INTO municipal_facility_aliases
                        (from_facility_id,to_facility_id,candidate_id,created_at,created_by)
                    VALUES (:fromId,:toId,:candidate,:now,:reviewer)
                    ON CONFLICT (from_facility_id) DO UPDATE SET
                        to_facility_id=EXCLUDED.to_facility_id,candidate_id=EXCLUDED.candidate_id,
                        created_at=EXCLUDED.created_at,created_by=EXCLUDED.created_by
                    """)
                    .param("fromId", superseded)
                    .param("toId", chosenFacilityId)
                    .param("candidate", candidate.id())
                    .param("now", Timestamp.from(now))
                    .param("reviewer", reviewer)
                    .update();
        }
    }

    @Override
    public void reopenLink(Candidate candidate, String reviewer, Instant now) {
        if (candidate.facilityAId() != null) {
            moveLink(candidate.sourceKeyA(), candidate.externalIdA(), candidate.facilityAId(), now);
        }
        if (candidate.facilityBId() != null) {
            moveLink(candidate.sourceKeyB(), candidate.externalIdB(), candidate.facilityBId(), now);
        }
        jdbc.sql("""
                DELETE FROM municipal_facility_aliases WHERE candidate_id=:candidate
                """).param("candidate", candidate.id()).update();
        jdbc.sql("""
                UPDATE municipal_parking_facilities
                SET lifecycle_state='ACTIVE',superseded_by_id=NULL,active=true,updated_at=:now,version=version+1
                WHERE id IN (:facilityA,:facilityB)
                """)
                .param("now", Timestamp.from(now))
                .param("facilityA", candidate.facilityAId())
                .param("facilityB", candidate.facilityBId())
                .update();
    }

    @Override
    public void upsertProvenance(FieldProvenanceSelection selection) {
        jdbc.sql("""
                INSERT INTO municipal_facility_field_provenance (
                    id,facility_id,field_name,source_key,source_record_id,source_content_ts,fetch_ts,
                    source_age_class,confidence_or_review_state,selection_reason,last_selected_at,version)
                VALUES (
                    :id,:facility,:field,:source,:record,:contentTs,:fetchTs,:age,:confidence,:reason,:selected,0)
                ON CONFLICT (facility_id,field_name) DO UPDATE SET
                    source_key=EXCLUDED.source_key,source_record_id=EXCLUDED.source_record_id,
                    source_content_ts=EXCLUDED.source_content_ts,fetch_ts=EXCLUDED.fetch_ts,
                    source_age_class=EXCLUDED.source_age_class,
                    confidence_or_review_state=EXCLUDED.confidence_or_review_state,
                    selection_reason=EXCLUDED.selection_reason,last_selected_at=EXCLUDED.last_selected_at,
                    version=municipal_facility_field_provenance.version+1
                """)
                .param("id", UUID.randomUUID())
                .param("facility", selection.facilityId())
                .param("field", selection.field().name())
                .param("source", selection.sourceKey())
                .param("record", selection.sourceRecordId())
                .param("contentTs", timestamp(selection.sourceContentTimestamp()))
                .param("fetchTs", Timestamp.from(selection.fetchTimestamp()))
                .param("age", selection.sourceAgeClass().name())
                .param("confidence", selection.confidenceOrReviewState())
                .param("reason", selection.selectionReason())
                .param("selected", Timestamp.from(selection.selectedAt()))
                .update();
    }

    @Override
    public Optional<ProvenanceRow> findProvenance(UUID facilityId, String fieldName) {
        return jdbc.sql("""
                SELECT facility_id, field_name, source_key, source_record_id, version
                FROM municipal_facility_field_provenance
                WHERE facility_id=:facility AND field_name=:field
                """)
                .param("facility", facilityId)
                .param("field", fieldName)
                .query((rs, rowNum) -> new ProvenanceRow(
                        rs.getObject("facility_id", UUID.class),
                        rs.getString("field_name"),
                        rs.getString("source_key"),
                        rs.getString("source_record_id"),
                        rs.getLong("version")))
                .optional();
    }

    @Override
    public boolean deleteProvenanceIfSourceOwns(UUID facilityId, String fieldName, String sourceKey) {
        int deleted = jdbc.sql("""
                DELETE FROM municipal_facility_field_provenance
                WHERE facility_id=:facility AND field_name=:field AND source_key=:source
                """)
                .param("facility", facilityId)
                .param("field", fieldName)
                .param("source", sourceKey)
                .update();
        return deleted > 0;
    }

    private void moveLink(String sourceKey, String externalId, UUID facilityId, Instant now) {
        jdbc.sql("""
                UPDATE municipal_facility_source_links link
                SET facility_id=:facility,updated_at=:now
                FROM municipal_data_sources source
                WHERE link.source_id=source.id AND source.source_key=:sourceKey
                  AND link.external_id=:externalId
                """)
                .param("facility", facilityId)
                .param("now", Timestamp.from(now))
                .param("sourceKey", sourceKey)
                .param("externalId", externalId)
                .update();
    }

    private static Candidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new Candidate(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_a_id", UUID.class),
                rs.getObject("facility_b_id", UUID.class),
                rs.getString("source_key_a"),
                rs.getString("external_id_a"),
                rs.getString("source_key_b"),
                rs.getString("external_id_b"),
                rs.getString("source_family_pair"),
                rs.getString("evidence_signals_json"),
                rs.getString("score_components_json"),
                rs.getDouble("total_score"),
                rs.getString("hard_conflicts"),
                instant(rs, "generated_at"),
                rs.getString("source_version_a"),
                rs.getString("source_version_b"),
                rs.getString("review_state"),
                rs.getString("reviewed_by"),
                instant(rs, "decision_ts"),
                rs.getString("rejection_reason"),
                rs.getObject("chosen_facility_id", UUID.class),
                rs.getString("algorithm_version"),
                rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
