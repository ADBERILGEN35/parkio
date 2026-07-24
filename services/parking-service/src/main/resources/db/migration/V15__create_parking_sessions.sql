-- Parking sessions are owned by parking-service. user_id is the platform auth-user
-- identifier and intentionally has no cross-service foreign key.
CREATE TABLE parking_sessions (
    id              UUID             NOT NULL,
    user_id         UUID             NOT NULL,
    status          VARCHAR(32)      NOT NULL,
    parking_source  VARCHAR(32)      NOT NULL,
    started_at      TIMESTAMPTZ      NOT NULL,
    ended_at        TIMESTAMPTZ,
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    location        GEOGRAPHY(Point, 4326) NOT NULL,
    estimated_fee   NUMERIC(12, 2),
    reminder_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ      NOT NULL,
    updated_at      TIMESTAMPTZ      NOT NULL,
    version         BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_parking_sessions PRIMARY KEY (id),
    CONSTRAINT ck_parking_sessions_status
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_parking_sessions_source
        CHECK (parking_source IN ('MANUAL', 'FACILITY', 'CURB', 'COMMUNITY', 'AUTO')),
    CONSTRAINT ck_parking_sessions_latitude
        CHECK (latitude BETWEEN -90.0 AND 90.0),
    CONSTRAINT ck_parking_sessions_longitude
        CHECK (longitude BETWEEN -180.0 AND 180.0),
    CONSTRAINT ck_parking_sessions_estimated_fee
        CHECK (estimated_fee IS NULL OR estimated_fee >= 0),
    CONSTRAINT ck_parking_sessions_lifecycle
        CHECK (
            (status = 'ACTIVE' AND ended_at IS NULL)
            OR
            (status IN ('COMPLETED', 'CANCELLED')
                AND ended_at IS NOT NULL
                AND ended_at >= started_at)
        )
);

-- Match the existing parking_spots convention: JPA maps latitude/longitude while
-- PostGIS derives the geography used by future spatial queries. Session location is
-- immutable, so the derived value is populated only when the row is inserted.
CREATE OR REPLACE FUNCTION parking_sessions_set_location() RETURNS trigger AS $$
BEGIN
    NEW.location := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::geography;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_parking_sessions_set_location
    BEFORE INSERT ON parking_sessions
    FOR EACH ROW EXECUTE FUNCTION parking_sessions_set_location();

-- Protect aggregate identity and creation facts even from direct SQL writes. Hibernate also
-- marks these columns as non-updatable, but the database remains the final invariant boundary.
CREATE OR REPLACE FUNCTION parking_sessions_reject_immutable_update() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.parking_source IS DISTINCT FROM OLD.parking_source
        OR NEW.started_at IS DISTINCT FROM OLD.started_at
        OR NEW.latitude IS DISTINCT FROM OLD.latitude
        OR NEW.longitude IS DISTINCT FROM OLD.longitude
        OR NOT ST_Equals(NEW.location::geometry, OLD.location::geometry)
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Parking session identity, source, location, and creation data are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_parking_sessions_immutable_fields';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_parking_sessions_reject_immutable_update
    BEFORE UPDATE ON parking_sessions
    FOR EACH ROW EXECUTE FUNCTION parking_sessions_reject_immutable_update();

-- The database is the concurrency authority for the zero-or-one ACTIVE invariant.
CREATE UNIQUE INDEX uq_parking_sessions_active_user
    ON parking_sessions (user_id)
    WHERE status = 'ACTIVE';

-- Owner history is terminal-only and returned newest session first.
CREATE INDEX idx_parking_sessions_user_history
    ON parking_sessions (user_id, started_at DESC, id DESC)
    WHERE status IN ('COMPLETED', 'CANCELLED');

CREATE INDEX idx_parking_sessions_location
    ON parking_sessions USING GIST (location);
