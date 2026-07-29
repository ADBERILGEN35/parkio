package com.parkio.parking.decision.audit;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.policy.DecisionEngine;
import java.util.Objects;

/**
 * Offline-only Decision Engine replay against a persisted audit snapshot.
 *
 * <p>Not invoked on the hot publication path. Resolves the exact policy and
 * engine versions bound on the record; unknown versions fail explicitly.
 */
public final class DecisionAuditReplayer {

    private DecisionAuditReplayer() {}

    public static DecisionResult replay(DecisionAuditRecord record) {
        Objects.requireNonNull(record, "record");
        DecisionEngine engine = DecisionEngineFactory.forVersions(
                record.policyVersion(), record.decisionEngineVersion());
        DecisionReplayInput input = record.toReplayInput();
        return engine.evaluate(input.evidence(), input.context());
    }

    public static DecisionReplayComparison replayAndCompare(DecisionAuditRecord record) {
        DecisionResult replayed = replay(record);
        return DecisionReplayComparison.of(record.decision(), replayed);
    }
}