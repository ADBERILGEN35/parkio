package com.parkio.parking.decision.assessment;

/**
 * Qualitative outcome of a domain assessment for one category.
 *
 * <p>Levels are not numeric scores. {@link #INSUFFICIENT_EVIDENCE} and
 * {@link #NOT_APPLICABLE} are distinct from {@link #UNCERTAIN} and from missing
 * categories on an {@link AssessmentBundle}.
 */
public enum AssessmentLevel {

    /** Confirmed positive signal supporting publication eligibility. */
    POSITIVE,

    /** Acceptable but not strongly positive. */
    ACCEPTABLE,

    /** Ambiguous or conflicting evidence within the category. */
    UNCERTAIN,

    /** Meaningful concern that elevates risk without necessarily blocking. */
    CONCERNING,

    /** Critical blocking concern for this category (may activate hard constraint). */
    CRITICAL,

    /** Category applies, but evidence snapshot does not meet minimum requirements. */
    INSUFFICIENT_EVIDENCE,

    /** Category is not relevant to the evaluated scenario (explicit N/A). */
    NOT_APPLICABLE
}