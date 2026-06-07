-- Image upload metadata owned by media-service. `owner_user_id` is an EXTERNAL
-- reference to the uploading user (auth/user-service id) — no cross-service
-- foreign key (ai-context/03). The bytes live in object storage; this table holds
-- only metadata, the storage object key, and a checksum for duplicate detection.
CREATE TABLE media_files (
    id              UUID         NOT NULL,
    owner_user_id   UUID         NOT NULL,
    bucket_name     VARCHAR(128) NOT NULL,
    object_key      VARCHAR(512) NOT NULL,
    access_url      VARCHAR(1024),
    content_type    VARCHAR(128) NOT NULL,
    file_size       BIGINT       NOT NULL,
    checksum        VARCHAR(128) NOT NULL,
    perceptual_hash VARCHAR(128),
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_media_files PRIMARY KEY (id),
    CONSTRAINT uq_media_files_checksum UNIQUE (checksum),
    CONSTRAINT uq_media_files_object_key UNIQUE (object_key)
);

-- Supports "list a user's media" lookups.
CREATE INDEX idx_media_files_owner_user_id ON media_files (owner_user_id);
