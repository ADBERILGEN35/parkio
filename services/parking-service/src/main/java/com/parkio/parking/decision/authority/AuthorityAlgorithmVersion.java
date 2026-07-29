package com.parkio.parking.decision.authority;

/** Versioned deterministic canary cohort algorithm identifier. */
public final class AuthorityAlgorithmVersion {

    public static final String V1 = "authority-canary-v1";

    private AuthorityAlgorithmVersion() {}

    public static boolean isKnown(String version) {
        return V1.equals(version);
    }
}