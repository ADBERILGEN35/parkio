-- DATA-WP-02: OSM Izmir parking facility import + conservative conflation.
-- Forward-only. Reuses municipal_* registry/facility/link tables (naming retained).

ALTER TABLE municipal_parking_facilities
    ADD COLUMN IF NOT EXISTS access_classification VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS primary_source_key VARCHAR(64);

ALTER TABLE municipal_parking_facilities
    DROP CONSTRAINT IF EXISTS ck_municipal_parking_facilities_access;
ALTER TABLE municipal_parking_facilities
    ADD CONSTRAINT ck_municipal_parking_facilities_access
        CHECK (access_classification IN (
            'PUBLIC', 'CUSTOMERS', 'PERMISSIVE', 'PRIVATE', 'RESIDENTS', 'RESTRICTED', 'UNKNOWN'));

CREATE TABLE municipal_osm_import_runs (
    id                          UUID            NOT NULL,
    sync_run_id                 UUID            NOT NULL,
    input_filename              VARCHAR(512)    NOT NULL,
    source_url                  VARCHAR(1024),
    source_published_at         TIMESTAMPTZ,
    downloaded_at               TIMESTAMPTZ,
    file_size_bytes             BIGINT,
    sha256                      VARCHAR(64)     NOT NULL,
    import_config_version       VARCHAR(64)     NOT NULL,
    clip_version                VARCHAR(64)     NOT NULL,
    dry_run                     BOOLEAN         NOT NULL DEFAULT FALSE,
    complete_success            BOOLEAN         NOT NULL DEFAULT FALSE,
    elements_read               INTEGER         NOT NULL DEFAULT 0,
    extracted                   INTEGER         NOT NULL DEFAULT 0,
    rejected                    INTEGER         NOT NULL DEFAULT 0,
    inserted                    INTEGER         NOT NULL DEFAULT 0,
    updated                     INTEGER         NOT NULL DEFAULT 0,
    unchanged                   INTEGER         NOT NULL DEFAULT 0,
    deactivated                 INTEGER         NOT NULL DEFAULT 0,
    reactivated                 INTEGER         NOT NULL DEFAULT 0,
    conflation_candidates       INTEGER         NOT NULL DEFAULT 0,
    auto_matched                INTEGER         NOT NULL DEFAULT 0,
    review_required             INTEGER         NOT NULL DEFAULT 0,
    rejected_matches            INTEGER         NOT NULL DEFAULT 0,
    hard_conflicts              INTEGER         NOT NULL DEFAULT 0,
    quality_report_json         TEXT,
    created_at                  TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_municipal_osm_import_runs PRIMARY KEY (id),
    CONSTRAINT fk_municipal_osm_import_runs_sync
        FOREIGN KEY (sync_run_id) REFERENCES municipal_source_sync_runs (id)
);

CREATE INDEX idx_municipal_osm_import_runs_sync
    ON municipal_osm_import_runs (sync_run_id);

CREATE TABLE municipal_facility_conflation_decisions (
    id                          UUID            NOT NULL,
    facility_a_id               UUID            NOT NULL,
    facility_b_id               UUID            NOT NULL,
    source_key_a                VARCHAR(64)     NOT NULL,
    source_key_b                VARCHAR(64)     NOT NULL,
    external_id_a               VARCHAR(128)    NOT NULL,
    external_id_b               VARCHAR(128)    NOT NULL,
    decision                    VARCHAR(32)     NOT NULL,
    decision_reason             VARCHAR(1024)   NOT NULL,
    policy_version              VARCHAR(64)     NOT NULL,
    signal_values_json          TEXT            NOT NULL,
    total_score                 DOUBLE PRECISION,
    automatic                   BOOLEAN         NOT NULL,
    actor                       VARCHAR(128),
    decided_at                  TIMESTAMPTZ     NOT NULL,
    superseded                  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_municipal_facility_conflation_decisions PRIMARY KEY (id),
    CONSTRAINT fk_municipal_conflation_facility_a
        FOREIGN KEY (facility_a_id) REFERENCES municipal_parking_facilities (id),
    CONSTRAINT fk_municipal_conflation_facility_b
        FOREIGN KEY (facility_b_id) REFERENCES municipal_parking_facilities (id),
    CONSTRAINT ck_municipal_conflation_decision
        CHECK (decision IN (
            'AUTO_MATCHED', 'REVIEW_REQUIRED', 'REJECTED', 'NOT_MATCHED',
            'MANUALLY_MATCHED', 'MANUALLY_REJECTED')),
    CONSTRAINT ck_municipal_conflation_pair_order
        CHECK (facility_a_id <> facility_b_id)
);

CREATE UNIQUE INDEX uq_municipal_conflation_active_pair
    ON municipal_facility_conflation_decisions (
        LEAST(facility_a_id, facility_b_id),
        GREATEST(facility_a_id, facility_b_id)
    )
    WHERE superseded = FALSE
      AND decision IN ('AUTO_MATCHED', 'MANUALLY_MATCHED', 'MANUALLY_REJECTED', 'REVIEW_REQUIRED');

CREATE INDEX idx_municipal_conflation_facility_a
    ON municipal_facility_conflation_decisions (facility_a_id);
CREATE INDEX idx_municipal_conflation_facility_b
    ON municipal_facility_conflation_decisions (facility_b_id);

-- OSM / Geofabrik Turkey registry entry (production_approved=false).
INSERT INTO municipal_data_sources (
    id, source_key, publisher, dataset_name, canonical_url, access_type,
    license_identifier, license_text, attribution_text, expected_update_frequency,
    stale_after_seconds, aging_after_seconds, schema_version, fields_used,
    active, production_approved, created_at, updated_at
) VALUES (
    '22222222-2222-2222-2222-222222222201',
    'osm-geofabrik-turkey',
    'OpenStreetMap contributors / Geofabrik GmbH',
    'Turkey OSM extract — amenity=parking (Izmir clip)',
    'https://download.geofabrik.de/europe/turkey.html',
    'OPEN_DATA_FILE',
    'ODbL-1.0',
    'Open Data Commons Open Database License (ODbL) v1.0. Redistribution and derived/collective database obligations require legal review before exposing bulk OSM-derived data to third parties. Table separation alone does not satisfy ODbL.',
    '© OpenStreetMap contributors',
    'weekly-extract',
    604800,
    86400,
    'osm-parking-geojson-v1',
    'osmType,osmId,name,operator,brand,geometry,centroid,parking,capacity,capacity:disabled,fee,access,opening_hours,maxstay,park_ride,supervised,covered,parking=*,amenity',
    TRUE,
    FALSE,
    TIMESTAMPTZ '2026-07-30T00:00:00Z',
    TIMESTAMPTZ '2026-07-30T00:00:00Z'
) ON CONFLICT (source_key) DO NOTHING;