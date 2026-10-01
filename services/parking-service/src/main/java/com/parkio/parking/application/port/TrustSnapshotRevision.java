package com.parkio.parking.application.port;

import com.parkio.parking.trust.TrustSnapshot;

/** A stored snapshot and the optimistic version observed with it. */
public record TrustSnapshotRevision(TrustSnapshot snapshot, long version) {}
