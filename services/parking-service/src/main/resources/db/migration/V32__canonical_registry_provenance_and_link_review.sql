-- DATA-WP-04: canonical facility registry provenance and conservative link review.
-- Forward-only. Registry metadata remains separate from short-lived occupancy observations.

ALTER TABLE municipal_parking_facilities
    ADD COLUMN lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN superseded_by_id UUID;

ALTER TABLE municipal_parking_facilities
    ADD CONSTRAINT ck_municipal_facility_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'SUPERSEDED')),
    ADD CONSTRAINT fk_municipal_facility_superseded_by
        FOREIGN KEY (superseded_by_id) REFERENCES municipal_parking_facilities (id),
    ADD CONSTRAINT ck_municipal_facility_supersession
        CHECK (
            (lifecycle_state = 'ACTIVE' AND superseded_by_id IS NULL)
            OR (lifecycle_state = 'SUPERSEDED' AND superseded_by_id IS NOT NULL AND superseded_by_id <> id)
        );

CREATE INDEX idx_municipal_facility_lifecycle
    ON municipal_parking_facilities (lifecycle_state);

CREATE TABLE municipal_facility_field_provenance (
    id                          UUID            NOT NULL,
    facility_id                 UUID            NOT NULL,
    field_name                  VARCHAR(64)     NOT NULL,
    source_key                  VARCHAR(64)     NOT NULL,
    source_record_id            VARCHAR(256)    NOT NULL,
    source_content_ts           TIMESTAMPTZ,
    fetch_ts                    TIMESTAMPTZ     NOT NULL,
    source_age_class            VARCHAR(32)     NOT NULL,
    confidence_or_review_state  VARCHAR(64)     NOT NULL,
    selection_reason            VARCHAR(512)    NOT NULL,
    last_selected_at            TIMESTAMPTZ     NOT NULL,
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_municipal_facility_field_provenance PRIMARY KEY (id),
    CONSTRAINT fk_municipal_field_provenance_facility
        FOREIGN KEY (facility_id) REFERENCES municipal_parking_facilities (id),
    CONSTRAINT uq_municipal_field_provenance_field UNIQUE (facility_id, field_name),
    CONSTRAINT ck_municipal_field_provenance_field CHECK (field_name IN (
        'NAME', 'COORDINATES', 'ADDRESS', 'DISTRICT', 'OPERATOR', 'FACILITY_TYPE',
        'ACCESS', 'STATIC_CAPACITY', 'OPENING_STATUS', 'ATTRIBUTION', 'TARIFF_ASSIGNMENT')),
    CONSTRAINT ck_municipal_field_provenance_age CHECK (source_age_class IN (
        'CURRENT', 'AGING', 'HISTORICAL', 'UNKNOWN'))
);

CREATE INDEX idx_municipal_field_provenance_source
    ON municipal_facility_field_provenance (source_key, source_record_id);

CREATE TABLE municipal_link_candidates (
    id                          UUID            NOT NULL,
    facility_a_id               UUID,
    facility_b_id               UUID,
    source_key_a                VARCHAR(64)     NOT NULL,
    external_id_a               VARCHAR(256)    NOT NULL,
    source_key_b                VARCHAR(64)     NOT NULL,
    external_id_b               VARCHAR(256)    NOT NULL,
    source_family_pair          VARCHAR(64)     NOT NULL,
    evidence_signals_json       JSONB           NOT NULL DEFAULT '{}'::jsonb,
    score_components_json       JSONB           NOT NULL DEFAULT '{}'::jsonb,
    total_score                 DOUBLE PRECISION NOT NULL,
    hard_conflicts              JSONB           NOT NULL DEFAULT '[]'::jsonb,
    generated_at                TIMESTAMPTZ     NOT NULL,
    source_version_a            VARCHAR(128)    NOT NULL,
    source_version_b            VARCHAR(128)    NOT NULL,
    review_state                VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    reviewed_by                 VARCHAR(128),
    decision_ts                 TIMESTAMPTZ,
    rejection_reason            VARCHAR(512),
    chosen_facility_id          UUID,
    algorithm_version           VARCHAR(64)     NOT NULL,
    version                     BIGINT          NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ     NOT NULL,
    updated_at                  TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_municipal_link_candidates PRIMARY KEY (id),
    CONSTRAINT fk_municipal_candidate_facility_a
        FOREIGN KEY (facility_a_id) REFERENCES municipal_parking_facilities (id),
    CONSTRAINT fk_municipal_candidate_facility_b
        FOREIGN KEY (facility_b_id) REFERENCES municipal_parking_facilities (id),
    CONSTRAINT fk_municipal_candidate_chosen_facility
        FOREIGN KEY (chosen_facility_id) REFERENCES municipal_parking_facilities (id),
    CONSTRAINT ck_municipal_candidate_distinct_sources CHECK (source_key_a <> source_key_b),
    CONSTRAINT ck_municipal_candidate_review_state CHECK (review_state IN (
        'PENDING', 'ACCEPTED', 'REJECTED', 'DISTINCT', 'REOPENED')),
    CONSTRAINT ck_municipal_candidate_score CHECK (total_score BETWEEN 0.0 AND 1.0),
    CONSTRAINT uq_municipal_candidate_source_versions UNIQUE (
        source_key_a, external_id_a, source_key_b, external_id_b,
        source_version_a, source_version_b, algorithm_version)
);

CREATE INDEX idx_municipal_candidate_queue
    ON municipal_link_candidates (review_state, generated_at, id);
CREATE INDEX idx_municipal_candidate_facility_a
    ON municipal_link_candidates (facility_a_id);
CREATE INDEX idx_municipal_candidate_facility_b
    ON municipal_link_candidates (facility_b_id);

CREATE TABLE municipal_link_review_audit (
    id                          UUID            NOT NULL,
    candidate_id                UUID            NOT NULL,
    previous_state              VARCHAR(32)     NOT NULL,
    new_state                   VARCHAR(32)     NOT NULL,
    reviewer                    VARCHAR(128)    NOT NULL,
    decision_reason             VARCHAR(512),
    chosen_facility_id          UUID,
    candidate_version           BIGINT          NOT NULL,
    decision_ts                 TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_municipal_link_review_audit PRIMARY KEY (id),
    CONSTRAINT fk_municipal_review_audit_candidate
        FOREIGN KEY (candidate_id) REFERENCES municipal_link_candidates (id),
    CONSTRAINT fk_municipal_review_audit_chosen_facility
        FOREIGN KEY (chosen_facility_id) REFERENCES municipal_parking_facilities (id)
);

CREATE INDEX idx_municipal_review_audit_candidate
    ON municipal_link_review_audit (candidate_id, decision_ts);

CREATE FUNCTION prevent_municipal_link_review_audit_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'municipal_link_review_audit is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_municipal_link_review_audit_immutable
    BEFORE UPDATE OR DELETE ON municipal_link_review_audit
    FOR EACH ROW EXECUTE FUNCTION prevent_municipal_link_review_audit_mutation();

CREATE TABLE municipal_facility_aliases (
    from_facility_id            UUID            NOT NULL,
    to_facility_id              UUID            NOT NULL,
    candidate_id                UUID,
    created_at                  TIMESTAMPTZ     NOT NULL,
    created_by                  VARCHAR(128)     NOT NULL,
    CONSTRAINT pk_municipal_facility_aliases PRIMARY KEY (from_facility_id),
    CONSTRAINT fk_municipal_alias_from
        FOREIGN KEY (from_facility_id) REFERENCES municipal_parking_facilities (id),
    CONSTRAINT fk_municipal_alias_to
        FOREIGN KEY (to_facility_id) REFERENCES municipal_parking_facilities (id),
    CONSTRAINT fk_municipal_alias_candidate
        FOREIGN KEY (candidate_id) REFERENCES municipal_link_candidates (id),
    CONSTRAINT ck_municipal_alias_not_self CHECK (from_facility_id <> to_facility_id)
);

CREATE INDEX idx_municipal_facility_alias_target
    ON municipal_facility_aliases (to_facility_id);
