-- An advisory AI validation result for a media object (and optionally the parking
-- spot it belongs to). `media_id` and `parking_spot_id` are EXTERNAL references
-- (media-service / parking-service ids) — no cross-service FK (ai-context/03).
-- ai-validation-service is an ADVISOR: it never rejects a spot or mutates other
-- services' data; it records a result and emits an advisory event. Scores are 0-100.
CREATE TABLE ai_validation_results (
    id                     UUID         NOT NULL,
    media_id               UUID         NOT NULL,
    parking_spot_id        UUID,
    requested_by_user_id   UUID,
    status                 VARCHAR(16)  NOT NULL,
    empty_space_confidence INTEGER      NOT NULL,
    legal_risk_score       INTEGER      NOT NULL,
    image_quality_score    INTEGER      NOT NULL,
    ai_confidence          INTEGER      NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_ai_validation_results PRIMARY KEY (id),
    CONSTRAINT ck_ai_validation_results_empty_space CHECK (empty_space_confidence BETWEEN 0 AND 100),
    CONSTRAINT ck_ai_validation_results_legal_risk CHECK (legal_risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_ai_validation_results_image_quality CHECK (image_quality_score BETWEEN 0 AND 100),
    CONSTRAINT ck_ai_validation_results_ai_confidence CHECK (ai_confidence BETWEEN 0 AND 100)
);

CREATE INDEX idx_ai_validation_results_media ON ai_validation_results (media_id, created_at DESC);
CREATE INDEX idx_ai_validation_results_parking ON ai_validation_results (parking_spot_id, created_at DESC);
