package com.parkio.auth.application.durable;

import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedCheckpoint;
import java.util.Objects;

/**
 * A consumer's high-water mark for checkpoints: freshness by {@code sequence}, never by wall
 * clock (contract §7). The mark is the consumer's own state, not something read from the store.
 * Once a checkpoint is accepted, an older valid checkpoint (a delayed object, or a store rolled
 * back to an older state) is refused, and so is a different ledger at the same sequence.
 * Signature, database identity and ledger digest are checked earlier, by
 * {@link DurableErasureEvidenceVerifier#verifyCheckpoint(byte[])}.
 */
public record CheckpointWatermark(long sequence, String ledgerDigest) {

    public CheckpointWatermark {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be >= 1: " + sequence);
        }
        Objects.requireNonNull(ledgerDigest, "ledgerDigest");
    }

    /** The mark after the consumer's first accepted checkpoint. */
    public static CheckpointWatermark first(VerifiedCheckpoint checkpoint) {
        return new CheckpointWatermark(checkpoint.sequence(), checkpoint.ledgerDigest());
    }

    /** Accepts {@code checkpoint} unless it is older than this mark; returns the new mark. */
    public CheckpointWatermark accept(VerifiedCheckpoint checkpoint) {
        if (checkpoint.sequence() < sequence) {
            throw new DurableEvidenceException("older valid checkpoint replayed; freshness failed");
        }
        if (checkpoint.sequence() == sequence && !ledgerDigest.equals(checkpoint.ledgerDigest())) {
            throw new DurableEvidenceException("conflicting checkpoint at the same sequence");
        }
        return first(checkpoint);
    }
}
