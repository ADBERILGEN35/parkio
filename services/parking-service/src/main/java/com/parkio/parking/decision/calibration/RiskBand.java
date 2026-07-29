package com.parkio.parking.decision.calibration;

/**
 * Bounded risk bands derived from decision-shadow-v1 thresholds.
 * Exact RiskScore must never be used as a metric tag.
 */
public enum RiskBand {
    UNKNOWN,
    LOW,
    ELEVATED,
    HIGH,
    CRITICAL
}
