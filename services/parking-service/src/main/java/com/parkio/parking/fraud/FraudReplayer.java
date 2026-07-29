package com.parkio.parking.fraud;

import java.util.Objects;

/** Offline-only replay for immutable fraud ledger entries. */
public final class FraudReplayer {

    private final FraudEngine engine = new FraudEngine();

    public FraudReplayComparison replay(FraudLedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        FraudSnapshot snapshot = entry.snapshot();
        if (!FraudPolicyConfig.POLICY_VERSION.equals(snapshot.policyVersion())) {
            throw new UnsupportedFraudPolicyVersionException("Unsupported fraud policy version: " + snapshot.policyVersion());
        }
        if (snapshot.snapshotSchemaVersion() != FraudSnapshotSchemaVersion.V1) {
            throw new UnsupportedFraudSchemaVersionException(
                    "Unsupported fraud snapshot schema: " + snapshot.snapshotSchemaVersion());
        }
        if (!FraudAggregationVersion.V1.equals(snapshot.aggregationVersion())) {
            throw new UnsupportedFraudSchemaVersionException(
                    "Unsupported fraud aggregation version: " + snapshot.aggregationVersion());
        }
        FraudEvaluation replayed = engine.evaluate(snapshot.featureVector(), snapshot.context());
        boolean identical = replayed.equals(entry.snapshot().evaluation());
        return new FraudReplayComparison(entry, replayed, identical);
    }
}
