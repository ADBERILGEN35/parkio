package com.parkio.parking.decision.audit;

/**
 * Bounded Decision Engine implementation version identifiers.
 *
 * <p>Distinct from policy version ({@code decision-shadow-v1}). Engine version
 * tracks pipeline/facade semantics; policy version tracks threshold tables.
 */
public final class DecisionEngineVersion {

    public static final String V1 = "decision-engine-v1";

    private DecisionEngineVersion() {}

    public static boolean isKnown(String version) {
        return V1.equals(version);
    }
}