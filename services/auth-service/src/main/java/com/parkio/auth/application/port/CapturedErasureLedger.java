package com.parkio.auth.application.port;

import com.parkio.auth.application.durable.ErasureLedgerEntry;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A committed lock-protocol capture of the tombstone ledger. {@code coveredThrough} is the
 * database clock while the SHARE lock was held: a human commit horizon, not an ordering key
 * (ordering and freshness use the checkpoint's sequence) and not part of the signed checkpoint.
 */
public record CapturedErasureLedger(List<ErasureLedgerEntry> entries, Instant coveredThrough) {

    public CapturedErasureLedger {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        Objects.requireNonNull(coveredThrough, "coveredThrough");
    }
}
