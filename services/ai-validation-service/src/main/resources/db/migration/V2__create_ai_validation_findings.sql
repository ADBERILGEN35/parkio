-- Individual advisory findings that make up a validation result (one per check the
-- placeholder validator ran). `risk_type` is set only for legal-risk findings. Scores
-- are 0-100. Child of ai_validation_results (same service, so a local FK is fine).
CREATE TABLE ai_validation_findings (
    id                   UUID         NOT NULL,
    validation_result_id UUID         NOT NULL,
    validation_type      VARCHAR(32)  NOT NULL,
    risk_type            VARCHAR(32),
    score                INTEGER      NOT NULL,
    message              VARCHAR(500),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_validation_findings PRIMARY KEY (id),
    CONSTRAINT fk_ai_validation_findings_result
        FOREIGN KEY (validation_result_id) REFERENCES ai_validation_results (id),
    CONSTRAINT ck_ai_validation_findings_score CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX idx_ai_validation_findings_result ON ai_validation_findings (validation_result_id);
