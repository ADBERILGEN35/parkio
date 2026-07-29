package com.parkio.parking.fraud;

import java.util.Objects;

/** Offline replay comparison for fraud ledger entries. */
public record FraudReplayComparison(
        FraudLedgerEntry original,
        FraudEvaluation replayed,
        boolean identical) {

    public FraudReplayComparison {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(replayed, "replayed");
    }
}
