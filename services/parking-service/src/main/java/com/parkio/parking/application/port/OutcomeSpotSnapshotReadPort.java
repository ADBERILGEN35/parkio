package com.parkio.parking.application.port;

import com.parkio.parking.outcome.normalization.OutcomeSpotSnapshotData;
import java.util.Optional;
import java.util.UUID;

public interface OutcomeSpotSnapshotReadPort {

    Optional<OutcomeSpotSnapshotData> findSpotSnapshot(UUID parkingSpotId);
}