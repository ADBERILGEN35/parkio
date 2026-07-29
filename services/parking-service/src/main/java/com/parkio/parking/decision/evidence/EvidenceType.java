package com.parkio.parking.decision.evidence;

/**
 * Canonical evidence taxonomy for Decision Engine evaluation.
 *
 * <p>Values name <em>categories</em> of observation, not concrete provider payloads.
 * Presence of a constant does <strong>not</strong> claim that Parkio currently
 * collects that signal (see WP-05.1 audit). Extending this enum MUST NOT couple
 * the Decision Engine to individual AI DTO fields.
 */
public enum EvidenceType {

    /** Photo / content analysis signals (e.g. AI validation findings). */
    AI_CONTENT_ANALYSIS,

    /** Location consistency, GPS quality, geospatial checks. */
    GEOSPATIAL_CONSISTENCY,

    /** Actor-level trust / reputation history (input only; not Evidence Score). */
    USER_TRUST_HISTORY,

    /** Device attestation / integrity signals (future). */
    DEVICE_INTEGRITY,

    /** Near-duplicate or replay detection. */
    DUPLICATE_DETECTION,

    /** Submission velocity / behavioral rate patterns. */
    BEHAVIOR_RATE,

    /** Network / collusion / fraud graph signals. */
    FRAUD_NETWORK,

    /** Post-publish user or system outcomes. */
    OUTCOME_FEEDBACK,

    /** Operational provenance (policy version, retries, SLA metadata). */
    OPERATIONAL_PROVENANCE
}
