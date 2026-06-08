-- Point-in-time contribution score per user (append-only history). For this
-- foundation the score tracks lifetime points; a future scheduled job will apply
-- the rolling-window decay described in ai-context/02.
CREATE TABLE contribution_snapshots (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    score       BIGINT       NOT NULL,
    captured_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_contribution_snapshots PRIMARY KEY (id)
);

CREATE INDEX idx_contribution_snapshots_user ON contribution_snapshots (user_id, captured_at DESC);
