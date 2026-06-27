package com.parkio.user.domain;

/** Current-day Smart Return plan state. No historical movement data is retained. */
public enum SmartReturnTodayStatus {
    UNKNOWN,
    LEFT_BY_CAR,
    RETURN_CHECK_IN_PROGRESS,
    NOT_BY_CAR,
    CANCELLED
}
