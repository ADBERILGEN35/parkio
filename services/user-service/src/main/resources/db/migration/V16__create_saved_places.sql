-- WP-SPA-03: user-owned saved places (HOME / WORK / CUSTOM).
-- Legacy Smart Return home columns on user_preferences are retained for dual-read/write.

CREATE TABLE saved_places (
    id UUID PRIMARY KEY,
    user_profile_id UUID NOT NULL REFERENCES user_profiles (id),
    kind VARCHAR(16) NOT NULL,
    label VARCHAR(512),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    source VARCHAR(32) NOT NULL,
    place_provider VARCHAR(64),
    place_provider_place_id VARCHAR(256),
    subtitle VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_saved_places_kind CHECK (kind IN ('HOME', 'WORK', 'CUSTOM')),
    CONSTRAINT chk_saved_places_source CHECK (source IN ('GEOCODING', 'MAP_PIN', 'SYSTEM')),
    CONSTRAINT chk_saved_places_latitude CHECK (latitude >= -90 AND latitude <= 90),
    CONSTRAINT chk_saved_places_longitude CHECK (longitude >= -180 AND longitude <= 180),
    CONSTRAINT chk_saved_places_custom_label CHECK (
        kind <> 'CUSTOM' OR (label IS NOT NULL AND btrim(label) <> '')
    ),
    CONSTRAINT chk_saved_places_place_identity_pair CHECK (
        (place_provider IS NULL AND place_provider_place_id IS NULL)
        OR (place_provider IS NOT NULL AND place_provider_place_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_saved_places_user_home
    ON saved_places (user_profile_id)
    WHERE kind = 'HOME';

CREATE UNIQUE INDEX uq_saved_places_user_work
    ON saved_places (user_profile_id)
    WHERE kind = 'WORK';

CREATE INDEX idx_saved_places_user_profile_id
    ON saved_places (user_profile_id);

CREATE INDEX idx_saved_places_user_updated
    ON saved_places (user_profile_id, updated_at DESC);
