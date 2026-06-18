package com.parkio.moderation.domain;

/**
 * The outcome a moderator applies when resolving a case. {@code APPROVE} dismisses
 * the case (no violation). The penalty actions cause moderation-service to emit
 * events that other services react to — it never mutates their data directly.
 */
public enum ModerationAction {
    APPROVE,
    REJECT,
    MARK_FILLED,
    MARK_RISKY,
    REDUCE_TRUST,
    DEDUCT_POINTS,
    SUSPEND_USER,
    RESTORE_USER;

    /** Whether this action penalises a user (recorded as a {@code UserViolation}). */
    public boolean isUserPenalty() {
        return this == SUSPEND_USER || this == REDUCE_TRUST || this == DEDUCT_POINTS;
    }

    /**
     * Whether this action requires the {@code ADMIN} role (separation of duties,
     * ai-context/07). Account sanctions and trust/score overrides — suspending or
     * restoring an account and reducing trust/points — change a user's standing and
     * carry real blast radius, so they are reserved to ADMIN. Ordinary content
     * decisions (approve/reject/mark) remain available to MODERATOR. Authorization
     * is enforced both in presentation and re-checked in the application service
     * (defense in depth), so this classification is the single source of truth.
     */
    public boolean requiresAdmin() {
        return this == SUSPEND_USER || this == RESTORE_USER
                || this == REDUCE_TRUST || this == DEDUCT_POINTS;
    }
}
