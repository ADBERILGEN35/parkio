CREATE TABLE fraud_evaluation_ledger (
    id                          UUID         NOT NULL,
    evaluation_id               UUID         NOT NULL,
    subject_type                VARCHAR(64)  NOT NULL,
    subject_id                  UUID         NOT NULL,
    fraud_domain                VARCHAR(64)  NOT NULL,
    policy_version              VARCHAR(64)  NOT NULL,
    schema_version              VARCHAR(64)  NOT NULL,
    mapping_version             VARCHAR(64)  NOT NULL,
    aggregation_version         VARCHAR(64)  NOT NULL,
    source_outcome_record_id    UUID         NOT NULL,
    evidence_window_start       TIMESTAMPTZ  NOT NULL,
    evidence_window_end         TIMESTAMPTZ  NOT NULL,
    risk_score                  INTEGER      NOT NULL,
    risk_band                   VARCHAR(64)  NOT NULL,
    confidence_band             VARCHAR(64)  NOT NULL,
    effective_evidence_count      INTEGER      NOT NULL,
    disposition                 VARCHAR(64)  NOT NULL,
    decisive_rule               VARCHAR(128) NOT NULL,
    evaluated_at                TIMESTAMPTZ  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    evaluation_snapshot_json    TEXT         NOT NULL,
    CONSTRAINT pk_fraud_evaluation_ledger PRIMARY KEY (id),
    CONSTRAINT uq_fraud_evaluation_ledger_evaluation UNIQUE (evaluation_id),
    CONSTRAINT uq_fraud_evaluation_ledger_trigger UNIQUE (source_outcome_record_id, subject_type, subject_id, fraud_domain, policy_version),
    CONSTRAINT fk_fraud_evaluation_ledger_outcome FOREIGN KEY (source_outcome_record_id) REFERENCES outcome_history (id),
    CONSTRAINT ck_fraud_evaluation_ledger_risk_non_negative CHECK (risk_score >= 0),
    CONSTRAINT ck_fraud_evaluation_ledger_risk_bounded CHECK (risk_score <= 10000),
    CONSTRAINT ck_fraud_evaluation_ledger_evidence_non_negative CHECK (effective_evidence_count >= 0)
);

CREATE INDEX idx_fraud_evaluation_ledger_subject_domain_evaluated
    ON fraud_evaluation_ledger (subject_type, subject_id, fraud_domain, evaluated_at, id);

CREATE INDEX idx_fraud_evaluation_ledger_outcome
    ON fraud_evaluation_ledger (source_outcome_record_id, subject_type, subject_id, fraud_domain);

CREATE INDEX idx_fraud_evaluation_ledger_policy
    ON fraud_evaluation_ledger (policy_version, schema_version, mapping_version, aggregation_version);
