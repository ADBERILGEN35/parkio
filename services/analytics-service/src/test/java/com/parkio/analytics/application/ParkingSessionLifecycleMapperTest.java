package com.parkio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.domain.exception.AnalyticsContractException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ParkingSessionLifecycleMapperTest {

    @Test
    void mapsWireTypesToCanonicalSnakeCaseNames() {
        assertThat(ParkingSessionLifecycleMapper.canonicalName("ParkingSessionStarted"))
                .isEqualTo("parking_session_started");
        assertThat(ParkingSessionLifecycleMapper.canonicalName("ParkingSessionCompleted"))
                .isEqualTo("parking_session_completed");
        assertThat(ParkingSessionLifecycleMapper.canonicalName("ParkingSessionCancelled"))
                .isEqualTo("parking_session_cancelled");
        assertThat(ParkingSessionLifecycleMapper.canonicalName("ParkingHistoryDeleted"))
                .isEqualTo("parking_session_history_deleted");
    }

    @Test
    void rejectsAliasWireNames() {
        assertThatThrownBy(() -> ParkingSessionLifecycleMapper.canonicalName("parking_session_started"))
                .isInstanceOf(AnalyticsContractException.class);
        assertThatThrownBy(() -> ParkingSessionLifecycleMapper.canonicalName("ParkingSessionEnded"))
                .isInstanceOf(AnalyticsContractException.class);
    }

    @Test
    void startedMetricDistinguishesManualAndCommunity() {
        assertThat(ParkingSessionLifecycleMapper.startedMetric("MANUAL"))
                .isEqualTo(AnalyticsMetricType.PARKING_SESSION_STARTED_MANUAL);
        assertThat(ParkingSessionLifecycleMapper.startedMetric("COMMUNITY"))
                .isEqualTo(AnalyticsMetricType.PARKING_SESSION_STARTED_COMMUNITY);
        assertThat(ParkingSessionLifecycleMapper.startedMetric("AUTO"))
                .isEqualTo(AnalyticsMetricType.PARKING_SESSION_STARTED_OTHER);
        assertThat(ParkingSessionLifecycleMapper.startedMetric("FACILITY"))
                .isEqualTo(AnalyticsMetricType.PARKING_SESSION_STARTED_OTHER);
    }

    @Test
    void durationSecondsDerivesFromProducerTimestamps() {
        Instant start = Instant.parse("2026-07-24T12:00:00Z");
        Instant end = Instant.parse("2026-07-24T12:30:00Z");
        assertThat(ParkingSessionLifecycleMapper.durationSeconds(start, end)).isEqualTo(1800L);
        assertThat(ParkingSessionLifecycleMapper.durationSeconds(start, start)).isZero();
    }

    @Test
    void durationSecondsRejectsNegative() {
        Instant start = Instant.parse("2026-07-24T12:30:00Z");
        Instant end = Instant.parse("2026-07-24T12:00:00Z");
        assertThatThrownBy(() -> ParkingSessionLifecycleMapper.durationSeconds(start, end))
                .isInstanceOf(AnalyticsContractException.class);
    }
}