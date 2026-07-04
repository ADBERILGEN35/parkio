-- Per-user trust score owned by gamification-service (ai-context/02, 03).
-- `user_id` is the platform-wide authUserId (EXTERNAL reference; no cross-service
-- FK). Range 0-100; starts at 100 to match user-service's default projection.
CREATE TABLE trust_scores (
    user_id    UUID        NOT NULL,
    score      INTEGER     NOT NULL DEFAULT 100,
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_trust_scores PRIMARY KEY (user_id),
    CONSTRAINT ck_trust_scores_range CHECK (score BETWEEN 0 AND 100)
);
