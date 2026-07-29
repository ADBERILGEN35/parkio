package com.parkio.parking.infrastructure.persistence.jpa;

import java.util.UUID;

/** Projection for trust-shadow batch claims from durable outcome history. */
public interface ValidatedOutcomeForTrustRow {

    UUID getId();

    UUID getParkingSpotId();

    UUID getOwnerUserId();
}

