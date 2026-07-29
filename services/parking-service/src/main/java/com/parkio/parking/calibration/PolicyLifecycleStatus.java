package com.parkio.parking.calibration;

public enum PolicyLifecycleStatus {
    EXPERIMENTAL,
    SHADOW,
    CANARY,
    AUTHORITATIVE_LIMITED,
    AUTHORITATIVE,
    RETIRED,
    REPLAY_ONLY
}