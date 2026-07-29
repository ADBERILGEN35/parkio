-- Structured rejection metadata for parking spots (additive; nullable for legacy rows).
-- last_ai_policy_version denormalizes the latest AI gate policy for legacy-reset eligibility
-- without cross-database joins to ai-validation-service.

ALTER TABLE parking_spots
    ADD COLUMN IF NOT EXISTS rejection_reason_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS rejection_reason_text VARCHAR(512),
    ADD COLUMN IF NOT EXISTS rejection_source VARCHAR(32),
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rejected_by UUID,
    ADD COLUMN IF NOT EXISTS rejection_policy_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS last_ai_policy_version VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_parking_spots_rejection_reason_code
    ON parking_spots (rejection_reason_code)
 WHERE rejection_reason_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_parking_spots_last_ai_policy_version
    ON parking_spots (last_ai_policy_version)
 WHERE last_ai_policy_version IS NOT NULL;
