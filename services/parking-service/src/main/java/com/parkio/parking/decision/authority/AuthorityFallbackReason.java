package com.parkio.parking.decision.authority;

/** Bounded reason a selected/considered evaluation was routed to legacy. */
public enum AuthorityFallbackReason {
    NOT_SELECTED,
    UNSUPPORTED_DISPOSITION,
    ENGINE_FAILURE,
    AUDIT_FAILURE,
    TRANSITION_CONFLICT,
    CONFIGURATION_FAILURE,
    LEGACY_REQUIRED,
    EVIDENCE_INCOMPLETE,
    HARD_CONSTRAINT,
    UNKNOWN
}