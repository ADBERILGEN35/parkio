package com.parkio.aivalidation.domain;

/**
 * How an advisory validation result was produced. Persisted for traceability so every
 * terminal moderation outcome is explainable (why it PASSED/FAILED/WARNING and whether
 * it came from a fresh model call, a reused prior result, an infrastructure fail-close,
 * or the placeholder heuristic).
 */
public enum DecisionSource {

    /** Fresh provider (vision model) call under the current version tuple. */
    AUTOMATED,

    /** Reused a prior persisted result whose version tuple matches the current one. */
    REUSED,

    /** Fail-closed (UNCERTAIN) due to timeout/outage/quota/malformed response, no model verdict. */
    INFRASTRUCTURE,

    /** Placeholder heuristic classifier (no vision provider configured). */
    HEURISTIC
}
