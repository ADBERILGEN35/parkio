package com.parkio.parking.application.port;

import com.parkio.parking.trust.TrustDomain;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSubject;
import java.util.Optional;

/** Reads current derived trust state. */
public interface TrustSnapshotReadPort {

    Optional<TrustSnapshot> findBySubjectAndDomain(TrustSubject subject, TrustDomain domain);
}

