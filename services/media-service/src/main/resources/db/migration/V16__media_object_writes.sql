-- U05 media erasure: durable ledger of object writes whose effect is not yet accounted for.
-- An upload records its PUT here, in its own committed transaction, before sending it.
--   PENDING: the outcome is unknown. The PUT may still be applied by the store at any later
--            time; no documented bound exists for when an accepted request completes.
--   APPLIED: the store confirmed the PUT, or its object has been observed; it cannot apply again.
-- The row is deleted once the object is accounted for elsewhere (the committed media row), or
-- is confirmed absent after it was applied, or the store definitively rejected the request.
-- A media erasure SUCCESS requires that the user owns no row here (see MediaObjectErasureWorker).
CREATE TABLE media_object_writes (
    id            UUID         NOT NULL,
    owner_user_id UUID         NOT NULL,
    bucket_name   VARCHAR(128) NOT NULL,
    object_key    VARCHAR(512) NOT NULL,
    state         VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_media_object_writes PRIMARY KEY (id),
    CONSTRAINT chk_media_object_writes_state CHECK (state IN ('PENDING', 'APPLIED'))
);

CREATE INDEX idx_media_object_writes_owner ON media_object_writes (owner_user_id);
