-- WP-05.8: distinguish shadow vs authoritative audit rows. Existing V20 rows backfill as SHADOW.
ALTER TABLE decision_audit ADD COLUMN execution_mode VARCHAR(32) NOT NULL DEFAULT 'SHADOW';
ALTER TABLE decision_audit ADD COLUMN authority_algorithm_version VARCHAR(64);
ALTER TABLE decision_audit ADD COLUMN canary_bucket INTEGER;
ALTER TABLE decision_audit ADD COLUMN authority_applied BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE decision_audit ADD COLUMN applied_status VARCHAR(32);

CREATE INDEX idx_decision_audit_execution_mode
    ON decision_audit (execution_mode, evaluated_at);

-- One authoritative applied row per evaluation+policy.
CREATE UNIQUE INDEX uq_decision_audit_authoritative_eval_policy
    ON decision_audit (evaluation_id, policy_version)
    WHERE execution_mode = 'AUTHORITATIVE' AND authority_applied = TRUE;