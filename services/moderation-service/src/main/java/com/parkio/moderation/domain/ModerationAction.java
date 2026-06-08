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
}
