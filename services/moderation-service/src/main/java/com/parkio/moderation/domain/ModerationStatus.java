package com.parkio.moderation.domain;

/** Lifecycle of a moderation case. */
public enum ModerationStatus {
    /** Newly opened, awaiting a moderator. */
    OPEN,
    /** Assigned to a moderator and under review. */
    IN_REVIEW,
    /** Closed with a decision/action upheld. */
    RESOLVED,
    /** Closed as dismissed — no violation found. */
    REJECTED;

    public boolean isTerminal() {
        return this == RESOLVED || this == REJECTED;
    }
}
