package com.parkio.auth.application.port;

public record DurableErasurePutResult(
        DurableErasureRecord record,
        boolean created,
        boolean conflict) {

    public static DurableErasurePutResult created(DurableErasureRecord record) {
        return new DurableErasurePutResult(record, true, false);
    }

    public static DurableErasurePutResult existing(DurableErasureRecord record) {
        return new DurableErasurePutResult(record, false, false);
    }

    public static DurableErasurePutResult conflict(DurableErasureRecord existing) {
        return new DurableErasurePutResult(existing, false, true);
    }
}
