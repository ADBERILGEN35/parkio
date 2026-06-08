package com.parkio.gamification.application;

/**
 * Stable reward/penalty rule keys. The handler logic decides <em>which</em> key
 * applies to which recipient; the point <em>value</em> for each key is looked up
 * from the seeded database rules (ai-context/02).
 */
public final class RewardRuleKeys {

    public static final String UPLOAD_OWNER = "PARKING_UPLOAD_OWNER";
    public static final String VERIFIED_OWNER = "PARKING_VERIFIED_OWNER";
    public static final String VERIFIED_VERIFIER = "PARKING_VERIFIED_VERIFIER";
    public static final String CLAIMED_OWNER = "PARKING_CLAIMED_OWNER";
    public static final String CLAIMED_CLAIMER = "PARKING_CLAIMED_CLAIMER";

    public static final String REJECTED_OWNER = "PARKING_REJECTED_OWNER";

    private RewardRuleKeys() {
    }
}
