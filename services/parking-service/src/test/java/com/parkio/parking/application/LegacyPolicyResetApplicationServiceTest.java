package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.port.ParkingSpotStatusHistoryRepository;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ModerationPolicy;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.RejectionReasonCode;
import com.parkio.parking.domain.RejectionSource;
import com.parkio.parking.domain.VehicleType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for the quiet legacy policy reset job — dry-run writes nothing;
 * execute rejects eligible inventory once with LEGACY_POLICY_RESET.
 */
class LegacyPolicyResetApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");
    private static final String TARGET = LegacyPolicyResetApplicationService.DEFAULT_TARGET_POLICY;
    private static final ModerationPolicy POLICY = new ModerationPolicy(
            Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMinutes(1), 3,
            Duration.ofMinutes(15), Duration.ofMinutes(30));

    private ParkingSpotRepository spots;
    private ParkingSpotStatusHistoryRepository history;
    private JdbcTemplate jdbc;
    private LegacyPolicyResetApplicationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        spots = mock(ParkingSpotRepository.class);
        history = mock(ParkingSpotStatusHistoryRepository.class);
        jdbc = mock(JdbcTemplate.class);
        service = new LegacyPolicyResetApplicationService(
                spots, history, jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
        when(jdbc.queryForList(anyString(), eq(TARGET))).thenReturn(List.of());
    }

    @Test
    void dryRunReportsEligibleCountsWithoutSaving() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("PENDING_VALIDATION"), eq(TARGET)))
                .thenReturn(2L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("ACTIVE"), eq(TARGET)))
                .thenReturn(1L);

        LegacyPolicyResetApplicationService.LegacyPolicyResetReport report =
                service.dryRun(TARGET, 10);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.eligibleCount()).isEqualTo(3L);
        assertThat(report.updatedCount()).isZero();
        assertThat(report.statusBreakdown().get("PENDING_VALIDATION")).isEqualTo(2L);
        assertThat(report.statusBreakdown().get("ACTIVE")).isEqualTo(1L);
        verify(spots, never()).save(any());
        verify(history, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeRejectsEligiblePendingAndActiveSpots() {
        ParkingSpot pending = createPending();
        ParkingSpot active = createActiveLegacy();
        UUID pendingId = pending.id();
        UUID activeId = active.id();

        when(jdbc.query(anyString(), any(RowMapper.class), eq(TARGET), anyInt()))
                .thenReturn(List.of(pendingId, activeId));
        when(spots.findById(pendingId)).thenReturn(Optional.of(pending));
        when(spots.findById(activeId)).thenReturn(Optional.of(active));
        when(spots.save(any(ParkingSpot.class))).thenAnswer(inv -> inv.getArgument(0));

        LegacyPolicyResetApplicationService.LegacyPolicyResetReport report =
                service.execute(TARGET, 50);

        assertThat(report.dryRun()).isFalse();
        assertThat(report.updatedCount()).isEqualTo(2);
        assertThat(pending.status()).isEqualTo(ParkingSpotStatus.REJECTED);
        assertThat(active.status()).isEqualTo(ParkingSpotStatus.REJECTED);
        assertThat(pending.rejection().code()).isEqualTo(RejectionReasonCode.LEGACY_POLICY_RESET);
        assertThat(active.rejection().source()).isEqualTo(RejectionSource.SYSTEM_MIGRATION);

        ArgumentCaptor<ParkingSpotStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(ParkingSpotStatusHistory.class);
        verify(history, org.mockito.Mockito.times(2)).save(historyCaptor.capture());
        assertThat(historyCaptor.getAllValues())
                .allMatch(h -> LegacyPolicyResetApplicationService.HISTORY_REASON.equals(h.reason()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeSkipsAlreadyRejectedAndTargetPolicySpots() {
        ParkingSpot alreadyRejected = createPending();
        alreadyRejected.applyAiValidationRejected(NOW);
        ParkingSpot onTarget = createActiveLegacy();
        onTarget.recordLastAiPolicyVersion(TARGET);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(TARGET), anyInt()))
                .thenReturn(List.of(alreadyRejected.id(), onTarget.id()));
        when(spots.findById(alreadyRejected.id())).thenReturn(Optional.of(alreadyRejected));
        when(spots.findById(onTarget.id())).thenReturn(Optional.of(onTarget));

        LegacyPolicyResetApplicationService.LegacyPolicyResetReport report =
                service.execute(TARGET, 50);

        assertThat(report.updatedCount()).isZero();
        verify(spots, never()).save(any());
        verify(history, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void secondExecuteIsIdempotentAfterMigrationReject() {
        ParkingSpot spot = createPending();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(TARGET), anyInt()))
                .thenReturn(List.of(spot.id()))
                .thenReturn(List.of(spot.id()));
        when(spots.findById(spot.id())).thenReturn(Optional.of(spot));
        when(spots.save(any(ParkingSpot.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.execute(TARGET, 10).updatedCount()).isEqualTo(1);
        assertThat(service.execute(TARGET, 10).updatedCount()).isZero();
    }

    private ParkingSpot createPending() {
        return ParkingSpot.create(
                UUID.randomUUID(), UUID.randomUUID(), 41.0, 29.0, null, null,
                false, Set.of(VehicleType.SEDAN), ParkingContext.STREET_PARKING,
                LegalStatus.LEGAL, Set.of(), NOW, POLICY);
    }

    private ParkingSpot createActiveLegacy() {
        ParkingSpot spot = createPending();
        assertThat(spot.applyAiValidationPassed(NOW, POLICY)).isTrue();
        return spot;
    }
}
