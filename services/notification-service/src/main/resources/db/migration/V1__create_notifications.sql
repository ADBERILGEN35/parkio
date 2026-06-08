-- In-app/push/email notifications owned by notification-service. `user_id` is the
-- platform-wide authUserId (an EXTERNAL reference; no cross-service FK, ai-context/03).
-- Content is denormalised (title/body) so it is stable regardless of later template
-- edits. `read_at` is set when the recipient reads it.
CREATE TABLE notifications (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    channel     VARCHAR(16)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        VARCHAR(1000) NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_notifications PRIMARY KEY (id)
);

-- "My notifications, newest first."
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
