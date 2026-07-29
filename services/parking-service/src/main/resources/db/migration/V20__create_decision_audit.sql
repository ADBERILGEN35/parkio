-- Append-only Decision Audit Store for completed shadow Decision Engine evaluations.
-- Snapshots are immutable: no UPDATE workflow. Corrections insert a new row.
-- snapshot_json holds canonical decision-domain values only (no raw AI/image payloads).
CREATE TABLE decision_audit (
    id                       UUID         NOT NULL,
    parking_spot_id          UUID         NOT NULL,
    evaluation_id            UUID         NOT NULL,
    policy_version           VARCHAR(64)  NOT NULL,
    decision_engine_version  VARCHAR(64)  NOT NULL,
    shadow_mode_version      VARCHAR(64)  NOT NULL,
    evaluated_at             TIMESTAMPTZ  NOT NULL,
    disposition              VARCHAR(32)  NOT NULL,
    comparison_category      VARCHAR(64)  NOT NULL,
    decisive_rule            VARCHAR(64)  NOT NULL,
    risk_band                VARCHAR(32)  NOT NULL,
    evidence_profile         VARCHAR(64)  NOT NULL,
    hard_constraint_family   VARCHAR(32)  NOT NULL,
    snapshot_json            TEXT         NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_decision_audit PRIMARY KEY (id),
    CONSTRAINT fk_decision_audit_spot
        FOREIGN KEY (parking_spot_id) REFERENCES parking_spots (id)
);

CREATE INDEX idx_decision_audit_spot_evaluated
    ON decision_audit (parking_spot_id, evaluated_at);

CREATE INDEX idx_decision_audit_evaluation
    ON decision_audit (evaluation_id);

CREATE INDEX idx_decision_audit_policy_evaluated
    ON decision_audit (policy_version, evaluated_at);

CREATE INDEX idx_decision_audit_evaluated_at
    ON decision_audit (evaluated_at);