package com.parkio.parking.outcome.replay;

import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeSnapshot;
import com.parkio.parking.outcome.engine.OutcomeValidationEngine;
import com.parkio.parking.outcome.policy.DefaultOutcomePolicy;
import com.parkio.parking.outcome.policy.OutcomePolicyConfig;
import com.parkio.parking.outcome.policy.OutcomePolicyVersion;
import java.util.Objects;

public final class OutcomeReplayer {

    private OutcomeReplayer() {}

    public static OutcomeEvaluation replay(OutcomeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        OutcomeValidationEngine engine = forPolicyVersion(snapshot.policyVersion());
        return engine.evaluate(snapshot.evidence(), snapshot.context());
    }

    public static OutcomeReplayComparison replayAndCompare(OutcomeSnapshot snapshot) {
        return OutcomeReplayComparison.of(snapshot.evaluation(), replay(snapshot));
    }

    public static OutcomeValidationEngine forPolicyVersion(OutcomePolicyVersion policyVersion) {
        Objects.requireNonNull(policyVersion, "policyVersion");
        if (!OutcomePolicyConfig.POLICY_VERSION.equals(policyVersion)) {
            throw new UnsupportedOutcomePolicyVersionException(policyVersion);
        }
        return new OutcomeValidationEngine(new DefaultOutcomePolicy(OutcomePolicyConfig.referenceV1()));
    }
}