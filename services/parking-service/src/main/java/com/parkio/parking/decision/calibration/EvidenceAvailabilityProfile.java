package com.parkio.parking.decision.calibration;

/**
 * Bounded runtime/golden evidence profile for the current v1 evidence types.
 * Does not treat missing future TRUST/device/H3 signals as a defect.
 */
public enum EvidenceAvailabilityProfile {
    UNKNOWN,
    AI_ONLY,
    AI_PLUS_OPERATIONAL,
    AI_PLUS_LOCATION,
    COMPLETE_CURRENT_V1,
    PARTIAL
}
