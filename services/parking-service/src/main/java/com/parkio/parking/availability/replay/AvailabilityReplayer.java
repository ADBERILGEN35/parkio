package com.parkio.parking.availability.replay;

import com.parkio.parking.availability.AvailabilityEvaluation;
import com.parkio.parking.availability.AvailabilitySnapshot;
import com.parkio.parking.availability.engine.AvailabilityEngine;
import com.parkio.parking.availability.policy.AvailabilityPolicyConfig;
import com.parkio.parking.availability.policy.AvailabilityPolicyVersion;
import com.parkio.parking.availability.policy.DefaultAvailabilityPolicy;
import java.util.Objects;

/**
 * Offline-only availability replay against a captured snapshot.
 */
public final class AvailabilityReplayer {

    private AvailabilityReplayer() {}

    public static AvailabilityEvaluation replay(AvailabilitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        AvailabilityEngine engine = forPolicyVersion(snapshot.policyVersion());
        return engine.evaluate(snapshot.evidence(), snapshot.context());
    }

    public static AvailabilityReplayComparison replayAndCompare(AvailabilitySnapshot snapshot) {
        AvailabilityEvaluation replayed = replay(snapshot);
        return AvailabilityReplayComparison.of(snapshot.evaluation(), replayed);
    }

    public static AvailabilityEngine forPolicyVersion(AvailabilityPolicyVersion policyVersion) {
        Objects.requireNonNull(policyVersion, "policyVersion");
        if (!AvailabilityPolicyConfig.POLICY_VERSION.equals(policyVersion)) {
            throw new UnsupportedAvailabilityPolicyVersionException(policyVersion);
        }
        return new AvailabilityEngine(new DefaultAvailabilityPolicy(AvailabilityPolicyConfig.referenceV1()));
    }
}
