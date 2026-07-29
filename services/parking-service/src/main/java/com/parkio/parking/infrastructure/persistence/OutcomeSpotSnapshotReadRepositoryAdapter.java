package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.outcome.normalization.OutcomeSpotSnapshotData;
import com.parkio.parking.application.port.OutcomeSpotSnapshotReadPort;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSpotJpaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutcomeSpotSnapshotReadRepositoryAdapter implements OutcomeSpotSnapshotReadPort {

    private final ParkingSpotJpaRepository jpa;

    public OutcomeSpotSnapshotReadRepositoryAdapter(ParkingSpotJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<OutcomeSpotSnapshotData> findSpotSnapshot(UUID parkingSpotId) {
        return jpa.findById(parkingSpotId)
                .map(entity -> new OutcomeSpotSnapshotData(entity.getId(), entity.getCreatedAt()));
    }
}