package com.parkio.parking.decision.evidence;

/**
 * Direction of an evidence item relative to publication eligibility.
 *
 * <p>{@link #ABSENT} is distinct from {@link #OPPOSES_PUBLISH}: missing signal must
 * not be treated as negative evidence.
 */
public enum EvidencePolarity {

    /** Observation supports publishing / credibility of the claim. */
    SUPPORTS_PUBLISH,

    /** Observation opposes publishing or indicates risk. */
    OPPOSES_PUBLISH,

    /** Observation is informative but neither supporting nor opposing. */
    NEUTRAL,

    /** Explicitly records that a signal was not observed (not a negative finding). */
    ABSENT
}
