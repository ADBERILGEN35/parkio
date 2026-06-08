-- Per-user gamification state owned by gamification-service. `user_id` is the
-- platform-wide authUserId (an EXTERNAL reference; no cross-service FK,
-- ai-context/03). One row per user; points start at 0, level at 1.
CREATE TABLE user_level_progress (
    user_id       UUID         NOT NULL,
    total_points  BIGINT       NOT NULL DEFAULT 0,
    current_level INTEGER      NOT NULL DEFAULT 1,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_level_progress PRIMARY KEY (user_id)
);

-- Leaderboard ordering.
CREATE INDEX idx_user_level_progress_points ON user_level_progress (total_points DESC);
