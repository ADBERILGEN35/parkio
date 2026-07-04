package com.parkio.gamification.application;

/**
 * Stable trust rule keys. Which key applies to which event is handler logic; the
 * signed delta for each key is looked up from the seeded database rules
 * (ai-context/02), mirroring {@link RewardRuleKeys}.
 */
public final class TrustRuleKeys {

    public static final String SPOT_VERIFIED_OWNER = "TRUST_SPOT_VERIFIED_OWNER";
    public static final String SPOT_CLAIMED_OWNER = "TRUST_SPOT_CLAIMED_OWNER";
    public static final String SPOT_REJECTED_OWNER = "TRUST_SPOT_REJECTED_OWNER";
    public static final String MODERATION_PENALTY = "TRUST_MODERATION_PENALTY";

    private TrustRuleKeys() {
    }
}
