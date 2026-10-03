package com.parkio.auth.application.port;

/**
 * Publishes signed checkpoints of the tombstone ledger (contract stage 2) to the durable store.
 */
public interface DurableErasureCheckpointStore {

    /**
     * Runs {@code capture} and publishes its ledger as a signed checkpoint with its own sequence.
     * The capture runs while this store admits no other sequence reservation, and the sequence
     * is reserved only after the capture committed. Every pending record with a lower sequence
     * was therefore reserved before the capture, after its tombstone had committed, so its
     * tombstone is in the checkpoint: the latest checkpoint plus the records above it is the whole
     * durably recorded erasure set. A failed capture publishes nothing.
     */
    DurableErasureCheckpoint publishCheckpoint(ErasureLedgerCapture capture);
}
