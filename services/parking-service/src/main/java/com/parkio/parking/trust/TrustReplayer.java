package com.parkio.parking.trust;

import java.util.Objects;

/** Offline-only replay for immutable trust ledger entries. */
public final class TrustReplayer {

    private final TrustEngine engine = new TrustEngine();

    public TrustReplayComparison replay(TrustLedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        TrustEvaluationContext context = new TrustEvaluationContext(
                entry.evaluatedAt(),
                entry.trustPolicyVersion(),
                entry.snapshotSchemaVersion());
        TrustEvaluation replayed = engine.evaluate(entry.previousSnapshot(), entry.evidence(), context);
        return new TrustReplayComparison(entry, replayed, replayed.equals(entry.evaluation()));
    }
}

