package com.parkio.parking.decision.authority;

import com.parkio.parking.domain.ParkingSpotStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure, framework-free authority eligibility + deterministic canary selection
 * (before DecisionEngine execution).
 */
public final class DecisionAuthoritySelector {

    private DecisionAuthoritySelector() {}

    public static DecisionAuthoritySelection select(
            boolean enabled,
            int canaryPercentage,
            String policyVersion,
            UUID parkingSpotId,
            UUID evaluationId,
            ParkingSpotStatus currentStatus,
            boolean staleEvent) {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(currentStatus, "currentStatus");
        AuthorityCanarySelector.requirePercentage(canaryPercentage);

        String algorithm = AuthorityAlgorithmVersion.V1;

        if (!enabled) {
            return DecisionAuthoritySelection.of(
                    false, false, AuthorityEligibilityReason.AUTHORITY_DISABLED,
                    policyVersion, algorithm, null, canaryPercentage);
        }
        if (canaryPercentage == 0) {
            return DecisionAuthoritySelection.of(
                    true, false, AuthorityEligibilityReason.ZERO_PERCENT_CANARY,
                    policyVersion, algorithm, null, canaryPercentage);
        }
        if (staleEvent) {
            return DecisionAuthoritySelection.of(
                    true, false, AuthorityEligibilityReason.STALE_EVENT,
                    policyVersion, algorithm, null, canaryPercentage);
        }
        if (currentStatus.isTerminal()) {
            return DecisionAuthoritySelection.of(
                    true, false, AuthorityEligibilityReason.ALREADY_FINALIZED,
                    policyVersion, algorithm, null, canaryPercentage);
        }
        if (currentStatus == ParkingSpotStatus.PENDING_REVIEW
                || currentStatus == ParkingSpotStatus.SUSPICIOUS) {
            return DecisionAuthoritySelection.of(
                    true, false, AuthorityEligibilityReason.MODERATOR_CONTROLLED,
                    policyVersion, algorithm, null, canaryPercentage);
        }
        if (currentStatus != ParkingSpotStatus.PENDING_VALIDATION) {
            return DecisionAuthoritySelection.of(
                    true, false, AuthorityEligibilityReason.UNSUPPORTED_CURRENT_STATUS,
                    policyVersion, algorithm, null, canaryPercentage);
        }

        int bucket = AuthorityCanarySelector.bucket(parkingSpotId, evaluationId);
        if (!AuthorityCanarySelector.isSelected(bucket, canaryPercentage)) {
            return DecisionAuthoritySelection.of(
                    true, false, AuthorityEligibilityReason.NOT_SELECTED,
                    policyVersion, algorithm, bucket, canaryPercentage);
        }
        return DecisionAuthoritySelection.of(
                true, true, AuthorityEligibilityReason.ELIGIBLE_SELECTED,
                policyVersion, algorithm, bucket, canaryPercentage);
    }
}