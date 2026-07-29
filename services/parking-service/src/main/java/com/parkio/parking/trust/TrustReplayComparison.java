package com.parkio.parking.trust;

import java.util.Objects;

/** Result of replaying a stored trust ledger entry. */
public record TrustReplayComparison(
        TrustLedgerEntry entry,
        TrustEvaluation replayedEvaluation,
        boolean identical) {

    public TrustReplayComparison {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(replayedEvaluation, "replayedEvaluation");
    }
}

