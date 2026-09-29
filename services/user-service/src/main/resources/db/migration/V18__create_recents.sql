-- WP-SPA-07: user-owned recent destinations and recently used parking.
-- Private behavioral history — not SavedPlace, favourites, or search-query logs.

CREATE TABLE recent_destinations (
    id UUID PRIMARY KEY,
    user_profile_id UUID NOT NULL REFERENCES user_profiles (id),
    label VARCHAR(512) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    source VARCHAR(32) NOT NULL,
    place_provider VARCHAR(64),
    place_provider_place_id VARCHAR(256),
    subtitle VARCHAR(256),
    duplicate_key VARCHAR(384) NOT NULL,
    first_used_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL,
    use_count BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_recent_destinations_source CHECK (source IN ('GEOCODING', 'MAP_PIN', 'SYSTEM')),
    CONSTRAINT chk_recent_destinations_latitude CHECK (latitude >= -90 AND latitude <= 90),
    CONSTRAINT chk_recent_destinations_longitude CHECK (longitude >= -180 AND longitude <= 180),
    CONSTRAINT chk_recent_destinations_label CHECK (btrim(label) <> ''),
    CONSTRAINT chk_recent_destinations_use_count CHECK (use_count >= 1),
    CONSTRAINT chk_recent_destinations_place_identity_pair CHECK (
        (place_provider IS NULL AND place_provider_place_id IS NULL)
        OR (place_provider IS NOT NULL AND place_provider_place_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_recent_destinations_user_dup
    ON recent_destinations (user_profile_id, duplicate_key);

CREATE INDEX idx_recent_destinations_user_last_used
    ON recent_destinations (user_profile_id, last_used_at DESC);

CREATE TABLE recent_parking (
    id UUID PRIMARY KEY,
    user_profile_id UUID NOT NULL REFERENCES user_profiles (id),
    target_kind VARCHAR(32) NOT NULL,
    target_id UUID NOT NULL,
    first_used_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL,
    use_count BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_recent_parking_target_kind CHECK (target_kind IN ('MUNICIPAL_FACILITY')),
    CONSTRAINT chk_recent_parking_use_count CHECK (use_count >= 1)
);

CREATE UNIQUE INDEX uq_recent_parking_user_target
    ON recent_parking (user_profile_id, target_kind, target_id);

CREATE INDEX idx_recent_parking_user_last_used
    ON recent_parking (user_profile_id, last_used_at DESC);
