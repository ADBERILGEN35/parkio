package com.parkio.analytics.domain;

/** The kinds of metric analytics-service tracks, one per upstream event of interest. */
public enum AnalyticsMetricType {
    PARKING_CREATED,
    PARKING_VERIFIED,
    PARKING_CLAIMED,
    PARKING_REJECTED,
    /** MANUAL ParkingSessionStarted → product {@code parking_session_started}. */
    PARKING_SESSION_STARTED_MANUAL,
    /** COMMUNITY ParkingSessionStarted → product {@code parking_session_started}. */
    PARKING_SESSION_STARTED_COMMUNITY,
    /** Non-MANUAL/COMMUNITY ParkingSessionStarted (FACILITY/CURB/AUTO). */
    PARKING_SESSION_STARTED_OTHER,
    PARKING_SESSION_COMPLETED,
    PARKING_SESSION_CANCELLED,
    /** ParkingHistoryDeleted → product {@code parking_session_history_deleted}. */
    PARKING_SESSION_HISTORY_DELETED,
    POINTS_EARNED,
    LEVEL_UP,
    NOTIFICATION_CREATED;

    /** Whether this metric belongs to the parking-spot funnel (drives the parking snapshot). */
    public boolean isParking() {
        return this == PARKING_CREATED || this == PARKING_VERIFIED
                || this == PARKING_CLAIMED || this == PARKING_REJECTED;
    }

    /** Whether this metric is a ParkingSession lifecycle observation (not the spot funnel). */
    public boolean isParkingSession() {
        return this == PARKING_SESSION_STARTED_MANUAL
                || this == PARKING_SESSION_STARTED_COMMUNITY
                || this == PARKING_SESSION_STARTED_OTHER
                || this == PARKING_SESSION_COMPLETED
                || this == PARKING_SESSION_CANCELLED
                || this == PARKING_SESSION_HISTORY_DELETED;
    }
}
