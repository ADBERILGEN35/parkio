package com.parkio.gamification.application;

/**
 * Stable keys for the seeded moderation penalty rules (V5). Point magnitudes are
 * data in the {@code penalty_rules} table, not code (ai-context/02).
 */
public final class PenaltyRuleKeys {

    public static final String FAKE = "PENALTY_FAKE";
    public static final String SPAM = "PENALTY_SPAM";

    private PenaltyRuleKeys() {
    }
}
