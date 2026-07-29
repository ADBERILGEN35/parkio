package com.parkio.parking.decision.assessment;

/**
 * How completely the evidence snapshot covers a category's expected signals.
 *
 * <p>Distinct from {@link AssessmentLevel}: a category may be COMPLETE yet
 * {@link AssessmentLevel#CONCERNING}, or PARTIAL yet {@link AssessmentLevel#POSITIVE}.
 */
public enum AssessmentCompleteness {

    /** All currently expected signals for the category were present and evaluated. */
    COMPLETE,

    /** Some expected optional signals were missing; evaluation still proceeded. */
    PARTIAL,

    /** No usable evidence items for the category were present. */
    EMPTY
}