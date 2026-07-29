CREATE TABLE trust_ledger (
    id                          UUID        NOT NULL,
    evaluation_id               UUID        NOT NULL,
    subject_type                VARCHAR(64) NOT NULL,
    subject_id                  UUID        NOT NULL,
    trust_domain                VARCHAR(64) NOT NULL,
    trust_policy_version        VARCHAR(64) NOT NULL,
    snapshot_schema_version     VARCHAR(64) NOT NULL,
    attribution_mapping_version VARCHAR(64) NOT NULL,
    source_outcome_record_id    UUID        NOT NULL,
    source_evidence_id          UUID        NOT NULL,
    source_evidence_group_id    UUID        NOT NULL,
    evidence_type               VARCHAR(64) NOT NULL,
    contribution_role           VARCHAR(64) NOT NULL,
    attribution_quality         VARCHAR(64) NOT NULL,
    eligibility                 VARCHAR(64) NOT NULL,
    update_direction            VARCHAR(64) NOT NULL,
    trust_level                 VARCHAR(64) NOT NULL,
    evaluated_at                TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    evidence_json               TEXT        NOT NULL,
    previous_snapshot_json      TEXT        NOT NULL,
    evaluation_json             TEXT        NOT NULL,
    CONSTRAINT pk_trust_ledger PRIMARY KEY (id),
    CONSTRAINT uq_trust_ledger_evaluation UNIQUE (evaluation_id),
    CONSTRAINT uq_trust_ledger_evidence UNIQUE (source_evidence_id),
    CONSTRAINT fk_trust_ledger_outcome FOREIGN KEY (source_outcome_record_id) REFERENCES outcome_history (id)
);

CREATE INDEX idx_trust_ledger_subject_domain_evaluated
    ON trust_ledger (subject_type, subject_id, trust_domain, evaluated_at, id);

CREATE INDEX idx_trust_ledger_outcome
    ON trust_ledger (source_outcome_record_id, subject_type, subject_id, trust_domain);

CREATE INDEX idx_trust_ledger_policy
    ON trust_ledger (trust_policy_version, snapshot_schema_version, attribution_mapping_version);

CREATE TABLE trust_snapshot (
    id                      UUID        NOT NULL,
    subject_type            VARCHAR(64) NOT NULL,
    subject_id              UUID        NOT NULL,
    trust_domain            VARCHAR(64) NOT NULL,
    trust_policy_version    VARCHAR(64) NOT NULL,
    snapshot_schema_version VARCHAR(64) NOT NULL,
    last_evaluated_at       TIMESTAMPTZ NOT NULL,
    snapshot_json           TEXT        NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_trust_snapshot PRIMARY KEY (id),
    CONSTRAINT uq_trust_snapshot_subject_domain UNIQUE (subject_type, subject_id, trust_domain)
);

CREATE INDEX idx_trust_snapshot_subject_domain
    ON trust_snapshot (subject_type, subject_id, trust_domain, last_evaluated_at);
