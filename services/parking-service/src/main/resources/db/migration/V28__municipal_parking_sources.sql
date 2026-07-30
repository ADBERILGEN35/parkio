-- DATA-WP-01: Municipal parking source foundation (Izmir IZUM first).
-- Forward-only additive schema. Does not alter community parking_spots.

CREATE TABLE municipal_data_sources (
    id                          UUID             NOT NULL,
    source_key                  VARCHAR(64)      NOT NULL,
    publisher                   VARCHAR(256)     NOT NULL,
    dataset_name                VARCHAR(256)     NOT NULL,
    canonical_url               VARCHAR(1024)    NOT NULL,
    access_type                 VARCHAR(32)      NOT NULL,
    license_identifier          VARCHAR(128)     NOT NULL,
    license_text                VARCHAR(1024)    NOT NULL,
    attribution_text            VARCHAR(1024)    NOT NULL,
    expected_update_frequency   VARCHAR(64)      NOT NULL,
    stale_after_seconds         INTEGER          NOT NULL,
    aging_after_seconds         INTEGER          NOT NULL,
    schema_version              VARCHAR(64)      NOT NULL,
    fields_used                 TEXT             NOT NULL,
    active                      BOOLEAN          NOT NULL DEFAULT TRUE,
    production_approved         BOOLEAN          NOT NULL DEFAULT FALSE,
    last_successful_sync_at     TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ      NOT NULL,
    updated_at                  TIMESTAMPTZ      NOT NULL,
    CONSTRAINT pk_municipal_data_sources PRIMARY KEY (id),
    CONSTRAINT uq_municipal_data_sources_key UNIQUE (source_key),
    CONSTRAINT ck_municipal_data_sources_access_type
        CHECK (access_type IN ('OPEN_API', 'OPEN_DATA_FILE', 'MANUAL')),
    CONSTRAINT ck_municipal_data_sources_stale_after
        CHECK (stale_after_seconds > 0),
    CONSTRAINT ck_municipal_data_sources_aging_after
        CHECK (aging_after_seconds > 0 AND aging_after_seconds <= stale_after_seconds)
);

CREATE TABLE municipal_source_sync_runs (
    id                      UUID             NOT NULL,
    source_id               UUID             NOT NULL,
    correlation_id          VARCHAR(64)      NOT NULL,
    started_at              TIMESTAMPTZ      NOT NULL,
    completed_at            TIMESTAMPTZ,
    status                  VARCHAR(32)      NOT NULL,
    records_received        INTEGER          NOT NULL DEFAULT 0,
    records_accepted        INTEGER          NOT NULL DEFAULT 0,
    records_rejected        INTEGER          NOT NULL DEFAULT 0,
    records_inserted        INTEGER          NOT NULL DEFAULT 0,
    records_updated         INTEGER          NOT NULL DEFAULT 0,
    records_unchanged       INTEGER          NOT NULL DEFAULT 0,
    occupancy_inserted      INTEGER          NOT NULL DEFAULT 0,
    error_category          VARCHAR(64),
    error_summary           VARCHAR(1024),
    schema_fingerprint      VARCHAR(128),
    payload_hash            VARCHAR(128),
    CONSTRAINT pk_municipal_source_sync_runs PRIMARY KEY (id),
    CONSTRAINT fk_municipal_source_sync_runs_source
        FOREIGN KEY (source_id) REFERENCES municipal_data_sources (id),
    CONSTRAINT ck_municipal_source_sync_runs_status
        CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_municipal_source_sync_runs_counts
        CHECK (
            records_received >= 0
            AND records_accepted >= 0
            AND records_rejected >= 0
            AND records_inserted >= 0
            AND records_updated >= 0
            AND records_unchanged >= 0
            AND occupancy_inserted >= 0
        )
);

CREATE UNIQUE INDEX uq_municipal_source_sync_runs_one_running
    ON municipal_source_sync_runs (source_id)
    WHERE status = 'RUNNING';

CREATE INDEX idx_municipal_source_sync_runs_source_started
    ON municipal_source_sync_runs (source_id, started_at DESC);

CREATE TABLE municipal_parking_facilities (
    id                  UUID             NOT NULL,
    operator_name       VARCHAR(256),
    facility_type       VARCHAR(32)      NOT NULL,
    display_name        VARCHAR(512)     NOT NULL,
    address_text        VARCHAR(1024),
    latitude            DOUBLE PRECISION NOT NULL,
    longitude           DOUBLE PRECISION NOT NULL,
    location            GEOGRAPHY(Point, 4326) NOT NULL,
    capacity_total      INTEGER,
    opening_hours_json  TEXT,
    is_paid             BOOLEAN,
    nonstop             BOOLEAN,
    active              BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ      NOT NULL,
    updated_at          TIMESTAMPTZ      NOT NULL,
    version             BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_municipal_parking_facilities PRIMARY KEY (id),
    CONSTRAINT ck_municipal_parking_facilities_type
        CHECK (facility_type IN ('ON_STREET', 'OFF_STREET', 'UNKNOWN')),
    CONSTRAINT ck_municipal_parking_facilities_lat
        CHECK (latitude BETWEEN -90.0 AND 90.0),
    CONSTRAINT ck_municipal_parking_facilities_lng
        CHECK (longitude BETWEEN -180.0 AND 180.0),
    CONSTRAINT ck_municipal_parking_facilities_capacity
        CHECK (capacity_total IS NULL OR capacity_total >= 0)
);

CREATE OR REPLACE FUNCTION municipal_parking_facilities_set_location() RETURNS trigger AS $$
BEGIN
    NEW.location := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::geography;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_municipal_parking_facilities_set_location
    BEFORE INSERT OR UPDATE OF latitude, longitude ON municipal_parking_facilities
    FOR EACH ROW EXECUTE FUNCTION municipal_parking_facilities_set_location();

CREATE INDEX idx_municipal_parking_facilities_location
    ON municipal_parking_facilities USING GIST (location);

CREATE INDEX idx_municipal_parking_facilities_active
    ON municipal_parking_facilities (active)
    WHERE active = TRUE;

CREATE TABLE municipal_facility_source_links (
    id                          UUID             NOT NULL,
    facility_id                 UUID             NOT NULL,
    source_id                   UUID             NOT NULL,
    external_id                 VARCHAR(128)     NOT NULL,
    source_name                 VARCHAR(512),
    source_latitude             DOUBLE PRECISION,
    source_longitude            DOUBLE PRECISION,
    source_metadata_json        TEXT,
    raw_record_hash             VARCHAR(128)     NOT NULL,
    first_seen_at               TIMESTAMPTZ      NOT NULL,
    last_seen_at                TIMESTAMPTZ      NOT NULL,
    last_successful_sync_at     TIMESTAMPTZ,
    active                      BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ      NOT NULL,
    updated_at                  TIMESTAMPTZ      NOT NULL,
    CONSTRAINT pk_municipal_facility_source_links PRIMARY KEY (id),
    CONSTRAINT fk_municipal_facility_source_links_facility
        FOREIGN KEY (facility_id) REFERENCES municipal_parking_facilities (id),
    CONSTRAINT fk_municipal_facility_source_links_source
        FOREIGN KEY (source_id) REFERENCES municipal_data_sources (id),
    CONSTRAINT uq_municipal_facility_source_links_source_ext
        UNIQUE (source_id, external_id)
);

CREATE INDEX idx_municipal_facility_source_links_facility
    ON municipal_facility_source_links (facility_id);

CREATE TABLE municipal_occupancy_snapshots (
    id                      UUID             NOT NULL,
    facility_id             UUID             NOT NULL,
    source_id               UUID             NOT NULL,
    source_link_id          UUID             NOT NULL,
    sync_run_id             UUID             NOT NULL,
    source_observed_at      TIMESTAMPTZ,
    fetched_at              TIMESTAMPTZ      NOT NULL,
    timestamp_provenance    VARCHAR(32)      NOT NULL,
    capacity_total          INTEGER,
    occupied_spaces         INTEGER,
    available_spaces        INTEGER,
    occupancy_status        VARCHAR(32)      NOT NULL,
    raw_record_hash         VARCHAR(128)     NOT NULL,
    created_at              TIMESTAMPTZ      NOT NULL,
    CONSTRAINT pk_municipal_occupancy_snapshots PRIMARY KEY (id),
    CONSTRAINT fk_municipal_occupancy_snapshots_facility
        FOREIGN KEY (facility_id) REFERENCES municipal_parking_facilities (id),
    CONSTRAINT fk_municipal_occupancy_snapshots_source
        FOREIGN KEY (source_id) REFERENCES municipal_data_sources (id),
    CONSTRAINT fk_municipal_occupancy_snapshots_link
        FOREIGN KEY (source_link_id) REFERENCES municipal_facility_source_links (id),
    CONSTRAINT fk_municipal_occupancy_snapshots_run
        FOREIGN KEY (sync_run_id) REFERENCES municipal_source_sync_runs (id),
    CONSTRAINT ck_municipal_occupancy_snapshots_provenance
        CHECK (timestamp_provenance IN ('SOURCE', 'FETCH')),
    CONSTRAINT ck_municipal_occupancy_snapshots_status
        CHECK (occupancy_status IN ('LIVE', 'AGING', 'STALE', 'UNAVAILABLE', 'INVALID')),
    CONSTRAINT ck_municipal_occupancy_snapshots_nonneg
        CHECK (
            (capacity_total IS NULL OR capacity_total >= 0)
            AND (occupied_spaces IS NULL OR occupied_spaces >= 0)
            AND (available_spaces IS NULL OR available_spaces >= 0)
        ),
    CONSTRAINT uq_municipal_occupancy_snapshots_dedupe
        UNIQUE (source_link_id, fetched_at, raw_record_hash)
);

CREATE INDEX idx_municipal_occupancy_snapshots_facility_fetched
    ON municipal_occupancy_snapshots (facility_id, fetched_at DESC);

CREATE INDEX idx_municipal_occupancy_snapshots_source_fetched
    ON municipal_occupancy_snapshots (source_id, fetched_at DESC);

INSERT INTO municipal_data_sources (
    id, source_key, publisher, dataset_name, canonical_url, access_type,
    license_identifier, license_text, attribution_text, expected_update_frequency,
    stale_after_seconds, aging_after_seconds, schema_version, fields_used,
    active, production_approved, created_at, updated_at
) VALUES (
    'a1111111-1111-4111-8111-111111111101',
    'izmir-izum-otoparklar',
    'Izmir Buyuksehir Belediyesi / IZUM',
    'Otopark Doluluk ve Lokasyon Bilgileri',
    'https://openapi.izmir.bel.tr/api/ibb/izum/otoparklar',
    'OPEN_API',
    'CC-BY-4.0',
    'Izmir Metropolitan Municipality Open Data License (CC BY 4.0)',
    'Includes public sector information from Izmir Buyuksehir Belediyesi Acik Veri Portali licensed under Attribution 4.0 International (CC BY 4.0). Parkio is not affiliated with or endorsed by Izmir Municipality or IZELMAN A.S.',
    'near-real-time',
    900,
    300,
    '2026-07-30.v1',
    'ufid,name,provider,type,status,lat,lng,occupancy.total.free,occupancy.total.occupied,openingHours,isPaid,nonstop,address',
    TRUE,
    FALSE,
    TIMESTAMPTZ '2026-07-30T00:00:00Z',
    TIMESTAMPTZ '2026-07-30T00:00:00Z'
);