package com.parkio.parking.application.port;

import com.parkio.parking.trust.TrustDomain;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSubject;
import java.util.function.Supplier;

/** Writes the derived trust snapshot projection. */
public interface TrustSnapshotWritePort {

    void upsert(TrustSnapshot snapshot);

    /**
     * Locks the subject's projection row (if it exists), then persists the snapshot
     * supplied while that lock is held so the fold cannot overwrite a newer ledger.
     */
    void replaceLocked(TrustSubject subject, TrustDomain domain, Supplier<TrustSnapshot> nextSnapshot);
}
