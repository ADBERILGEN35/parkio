package com.parkio.parking.outcome.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.outcome.signal.OutcomeSignalType;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutcomeTimelineFactoryTest {

    private static final UUID SPOT_ID = UUID.randomUUID();
    private static final Instant BASE = Instant.parse("2026-07-28T09:00:00Z");

    @Test
    void mapsStatusHistoryReasonsToSignals() {
        OutcomeTimeline timeline = OutcomeTimelineFactory.fromStatusHistory(
                BASE,
                BASE.plusSeconds(600),
                List.of(
                        ParkingSpotStatusHistory.record(SPOT_ID, null, ParkingSpotStatus.ACTIVE, "AI_PASSED", BASE),
                        ParkingSpotStatusHistory.record(
                                SPOT_ID,
                                ParkingSpotStatus.ACTIVE,
                                ParkingSpotStatus.VERIFIED,
                                "VERIFICATION_AVAILABLE",
                                BASE.plusSeconds(120)),
                        ParkingSpotStatusHistory.record(
                                SPOT_ID,
                                ParkingSpotStatus.VERIFIED,
                                ParkingSpotStatus.FILLED,
                                "CLAIMED",
                                BASE.plusSeconds(240))));

        assertThat(timeline.hasSignalType(OutcomeSignalType.AI_PUBLISHED)).isTrue();
        assertThat(timeline.hasSignalType(OutcomeSignalType.VERIFICATION_AVAILABLE)).isTrue();
        assertThat(timeline.hasSignalType(OutcomeSignalType.COMMUNITY_CLAIM)).isTrue();
        assertThat(timeline.latestSignalAt()).isEqualTo(BASE.plusSeconds(240));
    }
}