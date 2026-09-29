package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LinkCandidateGenerationRunAdapter implements LinkCandidateGenerationRunPort {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public LinkCandidateGenerationRunAdapter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> tryStart(StartRequest r) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO municipal_link_candidate_generation_runs(
                      id,source_family_pair,algorithm_version,dry_run,persist_candidates,
                      max_distance_meters,left_record_limit,pair_limit,sample_limit,left_scope_json,
                      status,operator_user_id,correlation_id,started_at)
                    VALUES (:id,:pair,:algorithm,:dryRun,:persist,:distance,:leftLimit,:pairLimit,
                      :sampleLimit,:scope,'RUNNING',:operator,:correlation,:started)
                    """)
                    .param("id", id).param("pair", r.sourceFamilyPair())
                    .param("algorithm", r.algorithmVersion()).param("dryRun", r.dryRun())
                    .param("persist", r.persistCandidates()).param("distance", r.maxDistanceMeters())
                    .param("leftLimit", r.leftRecordLimit()).param("pairLimit", r.pairLimit())
                    .param("sampleLimit", r.sampleLimit()).param("scope", r.leftScopeJson())
                    .param("operator", r.operatorUserId()).param("correlation", r.correlationId())
                    .param("started", Timestamp.from(r.startedAt())).update();
            return Optional.of(id);
        } catch (DataIntegrityViolationException conflict) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(
            UUID runId,
            String status,
            Aggregates a,
            String samplesJson,
            String failureCategory,
            Instant completedAt,
            long durationMs) {
        int changed = jdbc.sql("""
                UPDATE municipal_link_candidate_generation_runs SET
                  status=:status,left_records_considered=:leftRecords,pairs_considered=:pairs,
                  candidates_eligible=:eligible,candidates_persisted=:persisted,
                  hard_conflicts=:hardConflicts,skips_json=:skips,duplicates_suppressed=:duplicates,
                  failures=:failures,samples_json=:samples,failure_category=:failureCategory,
                  completed_at=:completed,duration_ms=:duration
                WHERE id=:id AND status='RUNNING'
                """)
                .param("status", status).param("leftRecords", a.leftRecordsConsidered())
                .param("pairs", a.pairsConsidered()).param("eligible", a.candidatesEligible())
                .param("persisted", a.candidatesPersisted()).param("hardConflicts", a.hardConflicts())
                .param("skips", json(a.skips())).param("duplicates", a.duplicatesSuppressed())
                .param("failures", a.failures()).param("samples", samplesJson)
                .param("failureCategory", failureCategory).param("completed", Timestamp.from(completedAt))
                .param("duration", durationMs).param("id", runId).update();
        if (changed != 1) throw new IllegalStateException("generation run is not RUNNING: " + runId);
    }

    @Override
    public Optional<RunRecord> findById(UUID id) {
        return jdbc.sql("SELECT * FROM municipal_link_candidate_generation_runs WHERE id=:id")
                .param("id", id).query(this::map).optional();
    }

    @Override
    public RunPage findPage(int page, int size, String pair) {
        if (page < 0 || size <= 0 || size > 100) throw new IllegalArgumentException("invalid page or size");
        boolean filtered = pair != null && !pair.isBlank();
        long total = jdbc.sql("""
                SELECT count(*) FROM municipal_link_candidate_generation_runs
                WHERE (:filtered=false OR source_family_pair=:pair)
                """)
                .param("filtered", filtered).param("pair", filtered ? pair : "").query(Long.class).single();
        List<RunRecord> content = jdbc.sql("""
                SELECT * FROM municipal_link_candidate_generation_runs
                WHERE (:filtered=false OR source_family_pair=:pair)
                ORDER BY started_at DESC,id DESC LIMIT :limit OFFSET :offset
                """)
                .param("filtered", filtered).param("pair", filtered ? pair : "")
                .param("limit", size).param("offset", page * size).query(this::map).list();
        return new RunPage(content, page, size, total);
    }

    @Override
    public int countActiveRunning() {
        return jdbc.sql("SELECT count(*) FROM municipal_link_candidate_generation_runs WHERE status='RUNNING'")
                .query(Integer.class).single();
    }

    @Override
    public Optional<RunRecord> findLatestCompleted() {
        return jdbc.sql("""
                SELECT * FROM municipal_link_candidate_generation_runs
                WHERE status IN ('COMPLETED','PARTIAL','FAILED')
                ORDER BY completed_at DESC NULLS LAST,id DESC LIMIT 1
                """).query(this::map).optional();
    }

    @Override
    public int countStaleRunning(Instant olderThan) {
        return jdbc.sql("""
                SELECT count(*) FROM municipal_link_candidate_generation_runs
                WHERE status='RUNNING' AND started_at<:olderThan
                """).param("olderThan", Timestamp.from(olderThan)).query(Integer.class).single();
    }

    private RunRecord map(ResultSet rs, int row) throws SQLException {
        Aggregates aggregates = new Aggregates(
                rs.getInt("left_records_considered"), rs.getInt("pairs_considered"),
                rs.getInt("candidates_eligible"), rs.getInt("candidates_persisted"),
                rs.getInt("hard_conflicts"), mapJson(rs.getString("skips_json")),
                rs.getInt("duplicates_suppressed"), rs.getInt("failures"));
        Timestamp completed = rs.getTimestamp("completed_at");
        Long duration = rs.getObject("duration_ms", Long.class);
        return new RunRecord(
                rs.getObject("id", UUID.class), rs.getString("source_family_pair"),
                rs.getString("algorithm_version"), rs.getBoolean("dry_run"),
                rs.getBoolean("persist_candidates"), rs.getDouble("max_distance_meters"),
                rs.getInt("left_record_limit"), rs.getInt("pair_limit"), rs.getInt("sample_limit"),
                rs.getString("left_scope_json"), rs.getString("status"), aggregates,
                rs.getString("samples_json"), rs.getString("failure_category"),
                rs.getString("operator_user_id"), rs.getString("correlation_id"),
                rs.getTimestamp("started_at").toInstant(),
                completed == null ? null : completed.toInstant(), duration);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("cannot serialize generation run", ex);
        }
    }

    private Map<String, Integer> mapJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
