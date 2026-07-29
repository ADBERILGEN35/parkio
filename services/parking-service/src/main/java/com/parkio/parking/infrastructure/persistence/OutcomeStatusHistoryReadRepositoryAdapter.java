package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.OutcomeStatusHistoryReadPort;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSpotStatusHistoryJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutcomeStatusHistoryReadRepositoryAdapter implements OutcomeStatusHistoryReadPort {

    private final ParkingSpotStatusHistoryJpaRepository jpa;

    public OutcomeStatusHistoryReadRepositoryAdapter(ParkingSpotStatusHistoryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<ParkingSpotStatusHistory> findStatusHistoryForOutcome(UUID parkingSpotId, Instant cutoffInclusive) {
        return jpa.findBySpotIdAndCreatedAtLessThanEqualOrderByCreatedAtAscIdAsc(parkingSpotId, cutoffInclusive).stream()
                .map(entity -> new ParkingSpotStatusHistory(
                        entity.getId(),
                        entity.getSpotId(),
                        entity.getPreviousStatus(),
                        entity.getNewStatus(),
                        entity.getReason(),
                        entity.getCreatedAt()))
                .toList();
    }
}