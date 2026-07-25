-- Adds traceability / provenance to advisory validation results so every terminal
-- moderation outcome is explainable and cross-version result reuse can be gated.
--
-- All columns are NULLABLE: this is a purely additive, non-blocking migration. Legacy
-- rows keep NULL provenance and are treated as an incomplete version tuple, so the
-- classifier re-runs under the current version rather than reusing an old decision.
-- No historical data is mutated or reprocessed here.
--
-- Hash columns store the full SHA-256 hex (64 chars) for correlation; application code
-- only ever logs a short, non-reversible prefix.
ALTER TABLE ai_validation_results
    ADD COLUMN decision_source          VARCHAR(32),
    ADD COLUMN provider                 VARCHAR(32),
    ADD COLUMN model_id                 VARCHAR(128),
    ADD COLUMN model_version            VARCHAR(128),
    ADD COLUMN prompt_version           VARCHAR(64),
    ADD COLUMN policy_version           VARCHAR(64),
    ADD COLUMN threshold_version        VARCHAR(64),
    ADD COLUMN canonical_image_hash     VARCHAR(64),
    ADD COLUMN raw_confidence           DOUBLE PRECISION,
    ADD COLUMN request_identity         VARCHAR(64),
    ADD COLUMN request_identity_version VARCHAR(16);

-- Correlate all results sharing one logical moderation request (identity tuple).
CREATE INDEX idx_ai_validation_results_request_identity
    ON ai_validation_results (request_identity);

-- Correlate submissions that used byte-identical canonical (normalized) image content.
CREATE INDEX idx_ai_validation_results_canonical_hash
    ON ai_validation_results (canonical_image_hash);
