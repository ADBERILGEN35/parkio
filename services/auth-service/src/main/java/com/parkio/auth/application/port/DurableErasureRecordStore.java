package com.parkio.auth.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Minimal durability port for enabled persist-before-COMPLETE.
 * There is no production directory or filesystem adapter. Absence of a bean
 * is a hard failure when the flag is on.
 */
public interface DurableErasureRecordStore {

    DurableErasurePutResult putIfAbsent(DurableErasureRecord record);

    Optional<DurableErasureRecord> findByRequestId(UUID erasureRequestId);
}
