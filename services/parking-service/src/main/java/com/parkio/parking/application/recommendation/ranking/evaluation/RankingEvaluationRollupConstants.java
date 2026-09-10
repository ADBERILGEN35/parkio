package com.parkio.parking.application.recommendation.ranking.evaluation;

/** Schema / exposure constants for WP-SPA-14D long-horizon rollups. */
public final class RankingEvaluationRollupConstants {

    public static final String EVALUATION_SCHEMA_VERSION = "PARKING_EVALUATION_ROLLUP_V1";
    public static final String EXPOSURE_POLICY = "DETERMINISTIC_ONLY";
    public static final String EVIDENCE_UNKNOWN = "UNKNOWN";
    public static final String EVIDENCE_ORGANIC = "ORGANIC";
    public static final String EVIDENCE_CONTROLLED_QA = "CONTROLLED_QA";
    public static final String SHADOW_VERSION_NONE = "NONE";
    public static final String OUTCOME_NONE = "NONE";
    /** Reporting/export small-cell threshold (internal DB retains exact counts). */
    public static final int MIN_REPORT_CELL_COUNT = 5;

    private RankingEvaluationRollupConstants() {}
}
