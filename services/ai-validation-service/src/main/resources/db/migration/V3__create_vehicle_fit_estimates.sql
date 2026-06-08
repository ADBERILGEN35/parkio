-- Advisory estimate of how well each vehicle type fits the detected space. fit_score
-- is 0-100. Child of ai_validation_results (same service, so a local FK is fine).
CREATE TABLE vehicle_fit_estimates (
    id                   UUID         NOT NULL,
    validation_result_id UUID         NOT NULL,
    vehicle_type         VARCHAR(16)  NOT NULL,
    fit_score            INTEGER      NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_vehicle_fit_estimates PRIMARY KEY (id),
    CONSTRAINT fk_vehicle_fit_estimates_result
        FOREIGN KEY (validation_result_id) REFERENCES ai_validation_results (id),
    CONSTRAINT ck_vehicle_fit_estimates_score CHECK (fit_score BETWEEN 0 AND 100)
);

CREATE INDEX idx_vehicle_fit_estimates_result ON vehicle_fit_estimates (validation_result_id);
