package com.parkio.auth.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * A published checkpoint. {@code filledReservation} is true when the checkpoint took the
 * sequence an earlier checkpoint had reserved but never published (a store failure or crash
 * between reservation and publication), which closes that gap below the frontier.
 */
public record DurableErasureCheckpoint(
        long sequence,
        int entryCount,
        String ledgerDigest,
        Instant coveredThrough,
        boolean filledReservation) {

    public DurableErasureCheckpoint {
        Objects.requireNonNull(ledgerDigest, "ledgerDigest");
        Objects.requireNonNull(coveredThrough, "coveredThrough");
    }
}
