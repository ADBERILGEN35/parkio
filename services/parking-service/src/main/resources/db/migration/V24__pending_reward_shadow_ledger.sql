CREATE TABLE pending_reward_ledger (
    id                          UUID         NOT NULL,
    evaluation_id               UUID         NOT NULL,
    reward_subject_type         VARCHAR(64)  NOT NULL,
    reward_subject_id           UUID         NOT NULL,
    contribution_role           VARCHAR(64)  NOT NULL,
    source_outcome_record_id    UUID         NOT NULL,
    source_contribution_id      UUID         NOT NULL,
    source_parking_spot_id      UUID         NOT NULL,
    evidence_group_id           UUID         NOT NULL,
    reward_policy_version       VARCHAR(64)  NOT NULL,
    attribution_mapping_version VARCHAR(64)  NOT NULL,
    snapshot_schema_version     VARCHAR(64)  NOT NULL,
    disposition                 VARCHAR(64)  NOT NULL,
    reward_unit                 VARCHAR(32)  NOT NULL,
    calculated_amount           INTEGER      NOT NULL,
    eligibility                 VARCHAR(64)  NOT NULL,
    primary_reason              VARCHAR(64)  NOT NULL,
    outcome_classification      VARCHAR(64)  NOT NULL,
    outcome_confidence_band     VARCHAR(32)  NOT NULL,
    evaluated_at                TIMESTAMPTZ  NOT NULL,
    evidence_cutoff_at          TIMESTAMPTZ  NOT NULL,
    contribution_json           TEXT         NOT NULL,
    evaluation_json             TEXT         NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_pending_reward_ledger PRIMARY KEY (id),
    CONSTRAINT uq_pending_reward_ledger_evaluation UNIQUE (evaluation_id),
    CONSTRAINT uq_pending_reward_ledger_contribution UNIQUE (source_contribution_id),
    CONSTRAINT fk_pending_reward_ledger_outcome FOREIGN KEY (source_outcome_record_id) REFERENCES outcome_history (id),
    CONSTRAINT fk_pending_reward_ledger_spot FOREIGN KEY (source_parking_spot_id) REFERENCES parking_spots (id),
    CONSTRAINT ck_pending_reward_ledger_amount_non_negative CHECK (calculated_amount >= 0)
);

CREATE INDEX idx_pending_reward_ledger_subject_evaluated
    ON pending_reward_ledger (reward_subject_type, reward_subject_id, evaluated_at, id);

CREATE INDEX idx_pending_reward_ledger_outcome
    ON pending_reward_ledger (source_outcome_record_id, reward_subject_type, reward_subject_id, contribution_role);

CREATE INDEX idx_pending_reward_ledger_spot_role
    ON pending_reward_ledger (source_parking_spot_id, reward_subject_type, reward_subject_id, contribution_role, reward_policy_version);
