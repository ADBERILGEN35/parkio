package com.parkio.parking.application.port;

import com.parkio.parking.trust.TrustSnapshot;

/** Writes the derived trust snapshot projection. */
public interface TrustSnapshotWritePort {

    void upsert(TrustSnapshot snapshot);
}

