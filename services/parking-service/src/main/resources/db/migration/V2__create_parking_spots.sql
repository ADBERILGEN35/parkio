-- Core parking spot owned by parking-service. `owner_user_id` and `media_id` are
-- EXTERNAL references (auth/user and media services) — no cross-service FK
-- (ai-context/03). Media bytes / storage internals never live here.
--
-- `latitude`/`longitude` are the source of truth (mapped by JPA). `location` is a
-- PostGIS geography maintained by a trigger from lat/lng and is used only for the
-- radius search index; it is intentionally NOT mapped by the JPA entity, which
-- keeps the entity DB-portable (H2 in tests) while production uses PostGIS.
CREATE TABLE parking_spots (
    id                     UUID             NOT NULL,
    owner_user_id          UUID             NOT NULL,
    media_id               UUID             NOT NULL,
    latitude               DOUBLE PRECISION NOT NULL,
    longitude              DOUBLE PRECISION NOT NULL,
    location               GEOGRAPHY(Point, 4326),
    address_text           VARCHAR(512),
    description            VARCHAR(1000),
    manual_location_edited BOOLEAN          NOT NULL DEFAULT FALSE,
    suitable_vehicle_types VARCHAR(255)     NOT NULL,
    parking_context        VARCHAR(32)      NOT NULL,
    legal_status           VARCHAR(32)      NOT NULL,
    violation_reasons      VARCHAR(512),
    status                 VARCHAR(32)      NOT NULL,
    confidence_score       DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    verification_count     INTEGER          NOT NULL DEFAULT 0,
    filled_report_count    INTEGER          NOT NULL DEFAULT 0,
    expires_at             TIMESTAMPTZ      NOT NULL,
    created_at             TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ      NOT NULL DEFAULT now(),
    version                BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_parking_spots PRIMARY KEY (id)
);

-- Keep the geography column in sync with lat/lng for the spatial index.
CREATE OR REPLACE FUNCTION parking_spots_sync_location() RETURNS trigger AS $$
BEGIN
    NEW.location := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::geography;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_parking_spots_sync_location
    BEFORE INSERT OR UPDATE OF latitude, longitude ON parking_spots
    FOR EACH ROW EXECUTE FUNCTION parking_spots_sync_location();

-- Geospatial index for nearby search, plus owner/status lookups.
CREATE INDEX idx_parking_spots_location ON parking_spots USING GIST (location);
CREATE INDEX idx_parking_spots_owner ON parking_spots (owner_user_id);
CREATE INDEX idx_parking_spots_status_expires ON parking_spots (status, expires_at);
