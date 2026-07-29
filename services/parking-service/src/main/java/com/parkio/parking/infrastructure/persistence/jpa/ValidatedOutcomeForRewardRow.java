package com.parkio.parking.infrastructure.persistence.jpa;

import java.util.UUID;

public interface ValidatedOutcomeForRewardRow {

    UUID getId();

    UUID getParkingSpotId();

    UUID getOwnerUserId();
}
