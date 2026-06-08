package com.parkio.analytics.domain;

/** The kinds of metric analytics-service tracks, one per upstream event of interest. */
public enum AnalyticsMetricType {
    PARKING_CREATED,
    PARKING_VERIFIED,
    PARKING_CLAIMED,
    PARKING_REJECTED,
    POINTS_EARNED,
    LEVEL_UP,
    NOTIFICATION_CREATED;

    /** Whether this metric belongs to the parking funnel (drives the parking snapshot). */
    public boolean isParking() {
        return this == PARKING_CREATED || this == PARKING_VERIFIED
                || this == PARKING_CLAIMED || this == PARKING_REJECTED;
    }
}
