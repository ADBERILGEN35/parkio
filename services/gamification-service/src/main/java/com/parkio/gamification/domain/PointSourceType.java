package com.parkio.gamification.domain;

/** What caused a point change. */
public enum PointSourceType {
    PARKING_UPLOAD,
    PARKING_VERIFIED,
    PARKING_CLAIMED,
    PARKING_FILLED_BY_USER,
    PENALTY_FAKE,
    PENALTY_SPAM,
    PENALTY_ILLEGAL_RISK
}
