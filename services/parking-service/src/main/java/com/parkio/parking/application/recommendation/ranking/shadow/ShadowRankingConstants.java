package com.parkio.parking.application.recommendation.ranking.shadow;

/** Schema / version constants for the WP-SPA-14 shadow ranking framework. */
public final class ShadowRankingConstants {

    public static final String FEATURE_SCHEMA_VERSION = "PARKING_SHADOW_FEATURES_V1";
    public static final String SHADOW_RANKER_VERSION = "LOCAL_CHALLENGER_V1";
    /** Recorded for schema stability even when the challenger is local (no LLM). */
    public static final String PROMPT_VERSION = "AI_SHADOW_PROMPT_V1";
    public static final String REQUEST_SCHEMA_VERSION = "PARKING_SHADOW_REQUEST_V1";
    public static final String OUTPUT_SCHEMA_VERSION = "PARKING_SHADOW_OUTPUT_V1";

    public static final int EVALUATION_STORE_CAPACITY = 256;

    private ShadowRankingConstants() {}
}
