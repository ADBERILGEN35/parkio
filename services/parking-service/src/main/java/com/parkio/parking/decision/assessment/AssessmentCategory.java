package com.parkio.parking.decision.assessment;

/**
 * Domain interpretation category for one evaluation.
 *
 * <p>Categories name assessment dimensions, not provider payloads. Reserved values
 * ({@link #TRUST}, {@link #BEHAVIOR}, {@link #AVAILABILITY}) exist for vocabulary
 * stability only — absence of a category in {@link AssessmentBundle} means the
 * category was not evaluated, not that a neutral or zero assessment exists.
 */
public enum AssessmentCategory {

    /** Photo / content credibility (vacancy, quality, AI advisory status). */
    CONTENT,

    /** Legal / placement risk interpretation. */
    LEGALITY,

    /** Geospatial validity and location consistency. */
    LOCATION,

    /** Integrity and operational provenance (identity match, staleness, correlation). */
    INTEGRITY,

    /** Actor trust (reserved; not evaluated in WP-05.4). */
    TRUST,

    /** Behavioral / velocity patterns (reserved). */
    BEHAVIOR,

    /** Opportunity freshness (reserved; distinct from publication validity). */
    AVAILABILITY
}