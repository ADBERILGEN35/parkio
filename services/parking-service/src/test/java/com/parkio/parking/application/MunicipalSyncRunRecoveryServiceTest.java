package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository.RecoveredRunView;
import com.parkio.parking.externalsource.MunicipalSourceFailureCategory;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MunicipalSyncRunRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-07T20:00:00Z");
    private static final UUID RUN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SOURCE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock MunicipalSourceSyncRunRepository runs;
    @Mock MunicipalSourceMetrics metrics;
    MunicipalSourceProperties properties;
    MunicipalSyncRunRecoveryService service;

    @BeforeEach
    void setUp() {
        properties = new MunicipalSourceProperties();
        properties.getSync().setStaleRunRecoveryEnabled(true);
        properties.getSync().setStaleRunningThreshold(Duration.ofMinutes(20));
        service = new MunicipalSyncRunRecoveryService(
                runs, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recoversStaleRowsAndRecordsMetrics() {
        Instant started = NOW.minus(Duration.ofHours(8));
        when(runs.recoverStaleRunning(eq(NOW.minus(Duration.ofMinutes(20))), eq(NOW)))
                .thenReturn(List.of(new RecoveredRunView(RUN, SOURCE, "izmir-izum-otoparklar", started)));

        assertThat(service.recoverStaleRunning()).isEqualTo(1);
        verify(metrics).recordStaleDetected(1);
        verify(metrics).recordStaleRecovered("izmir-izum-otoparklar");
        verify(metrics, never()).recordStaleRecoveryFailed();
    }

    @Test
    void zeroRowsIsIdempotentNoop() {
        when(runs.recoverStaleRunning(any(), any())).thenReturn(List.of());
        assertThat(service.recoverStaleRunning()).isZero();
        verify(metrics, never()).recordStaleDetected(anyInt());
        verify(metrics, never()).recordStaleRecovered(anyString());
    }

    @Test
    void disabledSkipsRepository() {
        properties.getSync().setStaleRunRecoveryEnabled(false);
        assertThat(service.recoverStaleRunning()).isZero();
        verify(runs, never()).recoverStaleRunning(any(), any());
    }

    @Test
    void repositoryFailureEmitsMetricWithoutThrowing() {
        when(runs.recoverStaleRunning(any(), any())).thenThrow(new RuntimeException("db"));
        assertThat(service.recoverStaleRunning()).isZero();
        verify(metrics).recordStaleRecoveryFailed();
    }

    @Test
    void categoryWireValueIsDistinct() {
        assertThat(MunicipalSourceFailureCategory.STALE_RUN_RECOVERED.wireValue())
                .isEqualTo("stale_run_recovered")
                .isNotEqualTo(MunicipalSourceFailureCategory.CANCELLED.wireValue())
                .isNotEqualTo(MunicipalSourceFailureCategory.READ_TIMEOUT.wireValue());
    }
}
