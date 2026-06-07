package com.parkio.parking.domain;

/** Why a spot may be illegal/risky (advisory flags). */
public enum ViolationReason {
    NO_PARKING_SIGN,
    GARAGE_ENTRANCE,
    BUS_STOP,
    PEDESTRIAN_CROSSING,
    FIRE_HYDRANT,
    SIDEWALK,
    TRAFFIC_FLOW_BLOCKING,
    PRIVATE_PROPERTY,
    OTHER
}
