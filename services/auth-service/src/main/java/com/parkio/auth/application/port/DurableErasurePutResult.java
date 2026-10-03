package com.parkio.auth.application.port;

/**
 * Outcome of a durable put. {@code receipt} describes the canonical stored version for the
 * request: the one just written, the one an identical retry found, or the conflicting one.
 */
public record DurableErasurePutResult(
        DurableErasureRecord record,
        boolean created,
        boolean conflict,
        DurableErasureReceipt receipt) {

    public static DurableErasurePutResult created(DurableErasureRecord record, DurableErasureReceipt receipt) {
        return new DurableErasurePutResult(record, true, false, receipt);
    }

    public static DurableErasurePutResult existing(DurableErasureRecord record, DurableErasureReceipt receipt) {
        return new DurableErasurePutResult(record, false, false, receipt);
    }

    public static DurableErasurePutResult conflict(DurableErasureRecord existing, DurableErasureReceipt receipt) {
        return new DurableErasurePutResult(existing, false, true, receipt);
    }
}
