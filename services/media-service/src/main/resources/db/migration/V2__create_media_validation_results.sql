-- Per-media validation outcomes (size, mime, duplicate now; AI safety/relevance
-- later). Append-only audit of what was checked and the result. FK is intra-service
-- only (same database), which ai-context/03 permits inside a service boundary.
CREATE TABLE media_validation_results (
    id              UUID         NOT NULL,
    media_id        UUID         NOT NULL,
    validation_type VARCHAR(32)  NOT NULL,
    result          VARCHAR(16)  NOT NULL,
    message         VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_media_validation_results PRIMARY KEY (id),
    CONSTRAINT fk_media_validation_results_media
        FOREIGN KEY (media_id) REFERENCES media_files (id)
);

CREATE INDEX idx_media_validation_results_media_id ON media_validation_results (media_id);
