-- WP-SPA-14D: privacy-safe long-horizon ranking evaluation rollups.
-- No evaluationId, candidate IDs, user/session/location, or feature JSON.

CREATE TABLE ranking_evaluation_rollup_slices (
    slice_start              TIMESTAMPTZ  NOT NULL,
    slice_end                TIMESTAMPTZ  NOT NULL,
    processed_at             TIMESTAMPTZ  NOT NULL,
    evaluations_processed    INTEGER      NOT NULL,
    outcomes_processed       INTEGER      NOT NULL,
    rollup_rows_written      INTEGER      NOT NULL,
    CONSTRAINT pk_ranking_evaluation_rollup_slices PRIMARY KEY (slice_start),
    CONSTRAINT chk_ranking_evaluation_rollup_slices_window
        CHECK (slice_end > slice_start),
    CONSTRAINT chk_ranking_evaluation_rollup_slices_counts
        CHECK (
            evaluations_processed >= 0
            AND outcomes_processed >= 0
            AND rollup_rows_written >= 0
        )
);

CREATE TABLE ranking_evaluation_daily_rollups (
    rollup_hour                    TIMESTAMPTZ  NOT NULL,
    rollup_date                    DATE         NOT NULL,
    platform                       VARCHAR(16)  NOT NULL,
    inventory_composition          VARCHAR(32)  NOT NULL,
    outcome_type                   VARCHAR(64)  NOT NULL,
    evidence_source                VARCHAR(32)  NOT NULL,
    deterministic_ranking_version  VARCHAR(64)  NOT NULL,
    shadow_ranker_version          VARCHAR(64)  NOT NULL,
    feature_schema_version         VARCHAR(64)  NOT NULL,
    evaluation_schema_version      VARCHAR(64)  NOT NULL,
    candidate_count_bucket         VARCHAR(16)  NOT NULL,
    freshness_mix                  VARCHAR(32)  NOT NULL,
    zero_availability_present      BOOLEAN      NOT NULL,
    high_capacity_present          BOOLEAN      NOT NULL,
    partial                        BOOLEAN      NOT NULL,
    exposure_policy                VARCHAR(32)  NOT NULL,

    evaluation_count               INTEGER      NOT NULL,
    outcome_count                  INTEGER      NOT NULL,
    shadow_attached_outcome_count  INTEGER      NOT NULL,

    deterministic_rank_sum         BIGINT       NOT NULL,
    shadow_rank_sum                BIGINT       NOT NULL,
    deterministic_top1_count       INTEGER      NOT NULL,
    deterministic_top3_count       INTEGER      NOT NULL,
    shadow_top1_count              INTEGER      NOT NULL,
    shadow_top3_count              INTEGER      NOT NULL,
    rank_delta_sum                 BIGINT       NOT NULL,
    rank_delta_count               INTEGER      NOT NULL,

    delta_le_m3                    INTEGER      NOT NULL,
    delta_m2                       INTEGER      NOT NULL,
    delta_m1                       INTEGER      NOT NULL,
    delta_0                        INTEGER      NOT NULL,
    delta_p1                       INTEGER      NOT NULL,
    delta_p2                       INTEGER      NOT NULL,
    delta_ge_p3                    INTEGER      NOT NULL,

    zero_availability_selected     INTEGER      NOT NULL,
    zero_availability_shadow_top1  INTEGER      NOT NULL,
    stale_static_present           INTEGER      NOT NULL,
    stale_static_selected          INTEGER      NOT NULL,
    stale_static_shadow_promoted   INTEGER      NOT NULL,

    CONSTRAINT pk_ranking_evaluation_daily_rollups PRIMARY KEY (
        rollup_hour,
        platform,
        inventory_composition,
        outcome_type,
        evidence_source,
        deterministic_ranking_version,
        shadow_ranker_version,
        feature_schema_version,
        evaluation_schema_version,
        candidate_count_bucket,
        freshness_mix,
        zero_availability_present,
        high_capacity_present,
        partial
    ),
    CONSTRAINT chk_ranking_evaluation_daily_rollups_platform
        CHECK (platform IN ('WEB', 'MOBILE_V2', 'UNKNOWN')),
    CONSTRAINT chk_ranking_evaluation_daily_rollups_outcome
        CHECK (outcome_type IN (
            'RECOMMENDATION_SELECTED',
            'NAVIGATION_STARTED',
            'PARKING_SESSION_STARTED',
            'RETURN_TO_CAR_STARTED',
            'PARKING_SESSION_ENDED',
            'NONE'
        )),
    CONSTRAINT chk_ranking_evaluation_daily_rollups_evidence
        CHECK (evidence_source IN ('UNKNOWN', 'ORGANIC', 'CONTROLLED_QA')),
    CONSTRAINT chk_ranking_evaluation_daily_rollups_exposure
        CHECK (exposure_policy = 'DETERMINISTIC_ONLY'),
    CONSTRAINT chk_ranking_evaluation_daily_rollups_nonneg
        CHECK (
            evaluation_count >= 0
            AND outcome_count >= 0
            AND shadow_attached_outcome_count >= 0
            AND deterministic_rank_sum >= 0
            AND shadow_rank_sum >= 0
            AND deterministic_top1_count >= 0
            AND deterministic_top3_count >= 0
            AND shadow_top1_count >= 0
            AND shadow_top3_count >= 0
            AND rank_delta_count >= 0
            AND delta_le_m3 >= 0
            AND delta_m2 >= 0
            AND delta_m1 >= 0
            AND delta_0 >= 0
            AND delta_p1 >= 0
            AND delta_p2 >= 0
            AND delta_ge_p3 >= 0
            AND zero_availability_selected >= 0
            AND zero_availability_shadow_top1 >= 0
            AND stale_static_present >= 0
            AND stale_static_selected >= 0
            AND stale_static_shadow_promoted >= 0
        )
);

CREATE INDEX idx_ranking_evaluation_daily_rollups_date
    ON ranking_evaluation_daily_rollups (rollup_date);
CREATE INDEX idx_ranking_evaluation_daily_rollups_hour
    ON ranking_evaluation_daily_rollups (rollup_hour);

CREATE TABLE ranking_evaluation_rollup_watermark (
    id                   SMALLINT     NOT NULL,
    completed_through    TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_ranking_evaluation_rollup_watermark PRIMARY KEY (id),
    CONSTRAINT chk_ranking_evaluation_rollup_watermark_singleton CHECK (id = 1)
);

INSERT INTO ranking_evaluation_rollup_watermark (id, completed_through, updated_at)
VALUES (1, TIMESTAMPTZ '1970-01-01T00:00:00Z', TIMESTAMPTZ '1970-01-01T00:00:00Z');
