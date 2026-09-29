-- DATA-WP-05: bounded cross-family registry candidate generation audit.
-- municipal_source_sync_runs is intentionally not reused: it describes per-source
-- occupancy/import synchronization, not cross-family candidate discovery.
CREATE TABLE municipal_link_candidate_generation_runs (
    id UUID PRIMARY KEY,
    source_family_pair VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    dry_run BOOLEAN NOT NULL,
    persist_candidates BOOLEAN NOT NULL,
    max_distance_meters DOUBLE PRECISION NOT NULL,
    left_record_limit INT NOT NULL,
    pair_limit INT NOT NULL,
    sample_limit INT NOT NULL,
    left_scope_json TEXT,
    status VARCHAR(32) NOT NULL,
    left_records_considered INT NOT NULL DEFAULT 0,
    pairs_considered INT NOT NULL DEFAULT 0,
    candidates_eligible INT NOT NULL DEFAULT 0,
    candidates_persisted INT NOT NULL DEFAULT 0,
    hard_conflicts INT NOT NULL DEFAULT 0,
    skips_json TEXT NOT NULL DEFAULT '{}',
    duplicates_suppressed INT NOT NULL DEFAULT 0,
    failures INT NOT NULL DEFAULT 0,
    samples_json TEXT NOT NULL DEFAULT '[]',
    failure_category VARCHAR(64),
    operator_user_id VARCHAR(128),
    correlation_id VARCHAR(128),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    CONSTRAINT ck_mlcg_runs_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_mlcg_runs_positive_bounds
        CHECK (max_distance_meters > 0 AND left_record_limit > 0 AND pair_limit > 0 AND sample_limit > 0)
);

CREATE UNIQUE INDEX uq_mlcg_runs_one_running
    ON municipal_link_candidate_generation_runs (source_family_pair)
    WHERE status = 'RUNNING';

CREATE INDEX idx_mlcg_runs_started_id
    ON municipal_link_candidate_generation_runs (started_at DESC, id DESC);

CREATE INDEX idx_mlcg_runs_pair_started_id
    ON municipal_link_candidate_generation_runs (source_family_pair, started_at DESC, id DESC);
