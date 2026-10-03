package com.parkio.auth.application.durable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code erased_user_tombstones} row in a checkpoint ledger: the entry shape of the #104
 * locked-snapshot output ({@code authUserId}, {@code erasedAt}), with {@code erasedAt} written
 * like every erasure time in the evidence format ({@link DurableErasureEvidence#erasedAt(Instant)}).
 */
public record ErasureLedgerEntry(UUID authUserId, Instant erasedAt) {

    public ErasureLedgerEntry {
        Objects.requireNonNull(authUserId, "authUserId");
        Objects.requireNonNull(erasedAt, "erasedAt");
    }
}
