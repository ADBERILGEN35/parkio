CREATE TABLE calibration_observation (
    id                          UUID         NOT NULL,
    observation_id              UUID         NOT NULL,
    engine_type                 VARCHAR(64)  NOT NULL,
    source_evaluation_id        UUID         NOT NULL,
    label_source_id             UUID         NOT NULL,
    policy_version              VARCHAR(64)  NOT NULL,
    schema_version              VARCHAR(64)  NOT NULL,
    mapping_version             VARCHAR(64)  NOT NULL,
    aggregation_version         VARCHAR(64)  NOT NULL,
    calibration_mapping_version VARCHAR(64)  NOT NULL,
    calibration_policy_version  VARCHAR(64)  NOT NULL,
    observation_horizon         VARCHAR(64)  NOT NULL,
    cohort_key                  VARCHAR(256) NOT NULL,
    attribution_quality         VARCHAR(64)  NOT NULL,
    label_quality               VARCHAR(64)  NOT NULL,
    label_finality              VARCHAR(64)  NOT NULL,
    predicted_at                TIMESTAMPTZ  NOT NULL,
    labeled_at                  TIMESTAMPTZ  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    observation_payload_json    TEXT         NOT NULL,
    CONSTRAINT pk_calibration_observation PRIMARY KEY (id),
    CONSTRAINT uq_calibration_observation_observation UNIQUE (observation_id),
    CONSTRAINT uq_calibration_observation_logical_key UNIQUE (engine_type, source_evaluation_id, label_source_id)
);

CREATE INDEX idx_calibration_observation_engine_predicted
    ON calibration_observation (engine_type, predicted_at, id);

CREATE INDEX idx_calibration_observation_cohort_predicted
    ON calibration_observation (cohort_key, predicted_at, id);

CREATE TABLE calibration_report (
    id                          UUID         NOT NULL,
    report_id                   UUID         NOT NULL,
    engine_type                 VARCHAR(64)  NOT NULL,
    baseline_policy_version     VARCHAR(64),
    candidate_policy_version    VARCHAR(64),
    calibration_policy_version  VARCHAR(64)  NOT NULL,
    window_start                TIMESTAMPTZ  NOT NULL,
    window_end                  TIMESTAMPTZ  NOT NULL,
    cohort_key                  VARCHAR(256) NOT NULL,
    observation_count           INTEGER      NOT NULL,
    labeled_count               INTEGER      NOT NULL,
    report_status               VARCHAR(64)  NOT NULL,
    source_watermark            TIMESTAMPTZ  NOT NULL,
    generated_at                TIMESTAMPTZ  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    report_payload_json         TEXT         NOT NULL,
    CONSTRAINT pk_calibration_report PRIMARY KEY (id),
    CONSTRAINT uq_calibration_report_report UNIQUE (report_id),
    CONSTRAINT uq_calibration_report_logical_key UNIQUE (engine_type, window_end, cohort_key, calibration_policy_version),
    CONSTRAINT ck_calibration_report_counts_non_negative CHECK (observation_count >= 0 AND labeled_count >= 0),
    CONSTRAINT ck_calibration_report_labeled_le_observation CHECK (labeled_count <= observation_count)
);

CREATE INDEX idx_calibration_report_engine_policy_generated
    ON calibration_report (engine_type, baseline_policy_version, generated_at, id);

CREATE INDEX idx_calibration_report_cohort_window
    ON calibration_report (cohort_key, window_start, window_end);

CREATE TABLE calibration_readiness_assessment (
    id                          UUID         NOT NULL,
    assessment_id               UUID         NOT NULL,
    engine_type                 VARCHAR(64)  NOT NULL,
    policy_version              VARCHAR(64)  NOT NULL,
    calibration_report_id       UUID         NOT NULL,
    readiness_status            VARCHAR(64)  NOT NULL,
    assessed_at                 TIMESTAMPTZ  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason_payload_json         TEXT         NOT NULL,
    CONSTRAINT pk_calibration_readiness_assessment PRIMARY KEY (id),
    CONSTRAINT uq_calibration_readiness_assessment_assessment UNIQUE (assessment_id),
    CONSTRAINT fk_calibration_readiness_assessment_report FOREIGN KEY (calibration_report_id) REFERENCES calibration_report (report_id)
);

CREATE INDEX idx_calibration_readiness_assessment_report
    ON calibration_readiness_assessment (calibration_report_id, assessed_at, id);
