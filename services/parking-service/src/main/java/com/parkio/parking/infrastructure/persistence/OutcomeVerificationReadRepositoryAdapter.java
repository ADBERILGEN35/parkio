package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.outcome.normalization.OutcomeVerificationSignalData;
import com.parkio.parking.application.port.OutcomeVerificationReadPort;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSpotVerificationJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutcomeVerificationReadRepositoryAdapter implements OutcomeVerificationReadPort {

    private final ParkingSpotVerificationJpaRepository jpa;

    public OutcomeVerificationReadRepositoryAdapter(ParkingSpotVerificationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<OutcomeVerificationSignalData> findVerificationsForOutcome(UUID parkingSpotId, Instant cutoffInclusive) {
        return jpa.findBySpotIdAndCreatedAtLessThanEqualOrderByCreatedAtAscIdAsc(parkingSpotId, cutoffInclusive).stream()
                .map(entity -> new OutcomeVerificationSignalData(entity.getId(), entity.getResult(), entity.getCreatedAt()))
                .toList();
    }
}