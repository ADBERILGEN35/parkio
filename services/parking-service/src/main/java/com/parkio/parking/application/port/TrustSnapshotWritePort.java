package com.parkio.parking.application.port;

import com.parkio.parking.trust.TrustSnapshot;

/** Writes the derived trust snapshot projection. */
public interface TrustSnapshotWritePort {

    /**
     * Insert when {@code expectedVersion} is null. Update only when the stored row still has that
     * version. A row that appears, or a version that moved, is a projection conflict.
     */
    void upsert(TrustSnapshot snapshot, Long expectedVersion);
}

