package com.parkio.parking.decision.audit;

/**
 * Bounded shadow orchestration mode version (feature semantics, not Spring flags).
 */
public final class ShadowModeVersion {

    public static final String V1 = "shadow-v1";

    private ShadowModeVersion() {}

    public static boolean isKnown(String version) {
        return V1.equals(version);
    }
}