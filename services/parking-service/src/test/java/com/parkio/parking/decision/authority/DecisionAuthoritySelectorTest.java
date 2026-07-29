package com.parkio.parking.decision.authority;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.decision.policy.ShadowDecisionPolicyConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DecisionAuthoritySelectorTest {

    private static final String POLICY = ShadowDecisionPolicyConfig.POLICY_VERSION.value();
    private static final UUID SPOT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVAL = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void disabledByDefaultPath() {
        DecisionAuthoritySelection s = DecisionAuthoritySelector.select(
                false, 50, POLICY, SPOT, EVAL, ParkingSpotStatus.PENDING_VALIDATION, false);
        assertThat(s.selected()).isFalse();
        assertThat(s.reason()).isEqualTo(AuthorityEligibilityReason.AUTHORITY_DISABLED);
    }

    @Test
    void zeroPercentNeverSelectedEvenWhenEnabled() {
        DecisionAuthoritySelection s = DecisionAuthoritySelector.select(
                true, 0, POLICY, SPOT, EVAL, ParkingSpotStatus.PENDING_VALIDATION, false);
        assertThat(s.selected()).isFalse();
        assertThat(s.reason()).isEqualTo(AuthorityEligibilityReason.ZERO_PERCENT_CANARY);
    }

    @Test
    void staleEventIneligible() {
        DecisionAuthoritySelection s = DecisionAuthoritySelector.select(
                true, 100, POLICY, SPOT, EVAL, ParkingSpotStatus.PENDING_VALIDATION, true);
        assertThat(s.selected()).isFalse();
        assertThat(s.reason()).isEqualTo(AuthorityEligibilityReason.STALE_EVENT);
    }

    @Test
    void terminalStatusFinalized() {
        DecisionAuthoritySelection s = DecisionAuthoritySelector.select(
                true, 100, POLICY, SPOT, EVAL, ParkingSpotStatus.REJECTED, false);
        assertThat(s.reason()).isEqualTo(AuthorityEligibilityReason.ALREADY_FINALIZED);
    }

    @Test
    void pendingReviewIsModeratorControlled() {
        DecisionAuthoritySelection s = DecisionAuthoritySelector.select(
                true, 100, POLICY, SPOT, EVAL, ParkingSpotStatus.PENDING_REVIEW, false);
        assertThat(s.reason()).isEqualTo(AuthorityEligibilityReason.MODERATOR_CONTROLLED);
    }

    @Test
    void eligibleSelectedAtOneHundredPercent() {
        DecisionAuthoritySelection s = DecisionAuthoritySelector.select(
                true, 100, POLICY, SPOT, EVAL, ParkingSpotStatus.PENDING_VALIDATION, false);
        assertThat(s.selected()).isTrue();
        assertThat(s.reason()).isEqualTo(AuthorityEligibilityReason.ELIGIBLE_SELECTED);
        assertThat(s.canaryBucket()).isPresent();
    }
}