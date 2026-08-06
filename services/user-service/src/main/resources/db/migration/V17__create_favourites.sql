-- WP-SPA-04: user-owned parking and destination favourites.
-- Reference-only parking favourites (no facility snapshot). Destination favourites
-- store a Destination-compatible snapshot with a deterministic duplicate_key.

CREATE TABLE favourite_parking (
    id UUID PRIMARY KEY,
    user_profile_id UUID NOT NULL REFERENCES user_profiles (id),
    target_kind VARCHAR(32) NOT NULL,
    target_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_favourite_parking_target_kind CHECK (target_kind IN ('MUNICIPAL_FACILITY'))
);

CREATE UNIQUE INDEX uq_favourite_parking_user_target
    ON favourite_parking (user_profile_id, target_kind, target_id);

CREATE INDEX idx_favourite_parking_user_created
    ON favourite_parking (user_profile_id, created_at DESC);

CREATE TABLE favourite_destinations (
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
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_favourite_destinations_source CHECK (source IN ('GEOCODING', 'MAP_PIN', 'SYSTEM')),
    CONSTRAINT chk_favourite_destinations_latitude CHECK (latitude >= -90 AND latitude <= 90),
    CONSTRAINT chk_favourite_destinations_longitude CHECK (longitude >= -180 AND longitude <= 180),
    CONSTRAINT chk_favourite_destinations_label CHECK (btrim(label) <> ''),
    CONSTRAINT chk_favourite_destinations_place_identity_pair CHECK (
        (place_provider IS NULL AND place_provider_place_id IS NULL)
        OR (place_provider IS NOT NULL AND place_provider_place_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_favourite_destinations_user_dup
    ON favourite_destinations (user_profile_id, duplicate_key);

CREATE INDEX idx_favourite_destinations_user_updated
    ON favourite_destinations (user_profile_id, updated_at DESC);
