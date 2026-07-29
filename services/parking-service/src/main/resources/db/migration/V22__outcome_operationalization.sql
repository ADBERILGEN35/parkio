CREATE TABLE outcome_history (
    id                      UUID         NOT NULL,
    evaluation_id           UUID         NOT NULL,
    parking_spot_id         UUID         NOT NULL,
    policy_version          VARCHAR(64)  NOT NULL,
    snapshot_schema_version VARCHAR(64)  NOT NULL,
    trigger_type            VARCHAR(64)  NOT NULL,
    trigger_reference       UUID         NOT NULL,
    evaluated_at            TIMESTAMPTZ  NOT NULL,
    evidence_cutoff_at      TIMESTAMPTZ  NOT NULL,
    classification          VARCHAR(64)  NOT NULL,
    confidence              INTEGER      NOT NULL,
    primary_reason          VARCHAR(64)  NOT NULL,
    validation_window_open  BOOLEAN      NOT NULL,
    snapshot_json           TEXT         NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_outcome_history PRIMARY KEY (id),
    CONSTRAINT uq_outcome_history_evaluation UNIQUE (evaluation_id),
    CONSTRAINT fk_outcome_history_spot FOREIGN KEY (parking_spot_id) REFERENCES parking_spots (id)
);

CREATE INDEX idx_outcome_history_spot_evaluated
    ON outcome_history (parking_spot_id, evaluated_at, id);

CREATE INDEX idx_outcome_history_cutoff
    ON outcome_history (parking_spot_id, evidence_cutoff_at, id);

CREATE TABLE outcome_evaluation_triggers (
    id                 UUID         NOT NULL,
    evaluation_id      UUID         NOT NULL,
    parking_spot_id    UUID         NOT NULL,
    trigger_type       VARCHAR(64)  NOT NULL,
    trigger_reference  UUID         NOT NULL,
    evidence_cutoff_at TIMESTAMPTZ  NOT NULL,
    processed          BOOLEAN      NOT NULL DEFAULT FALSE,
    processed_at       TIMESTAMPTZ,
    failure_count      INTEGER      NOT NULL DEFAULT 0,
    last_failure_stage VARCHAR(64),
    last_failed_at     TIMESTAMPTZ,
    dead_lettered      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_outcome_evaluation_triggers PRIMARY KEY (id),
    CONSTRAINT uq_outcome_evaluation_triggers_evaluation UNIQUE (evaluation_id),
    CONSTRAINT fk_outcome_evaluation_triggers_spot FOREIGN KEY (parking_spot_id) REFERENCES parking_spots (id)
);

CREATE INDEX idx_outcome_eval_triggers_pending
    ON outcome_evaluation_triggers (processed, dead_lettered, created_at, id);

CREATE INDEX idx_outcome_eval_triggers_spot
    ON outcome_evaluation_triggers (parking_spot_id, created_at, id);

CREATE INDEX idx_parking_spot_status_history_created_at_id
    ON parking_spot_status_history (created_at, id);

CREATE INDEX idx_parking_spot_verifications_created_at_id
    ON parking_spot_verifications (created_at, id);