package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MunicipalOccupancyRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    @Test
    void backupGateBlocksDeletion() {
        MunicipalOccupancySnapshotRepository snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        MunicipalOccupancyRetentionService service = new MunicipalOccupancyRetentionService(
                snapshots,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(7),
                1000,
                10,
                true,
                () -> "backup_complete_missing",
                new MunicipalOccupancyRetentionService.Metrics() {
                    @Override
                    public void recordSuccess(int deletedRows, long durationMs, Instant successAt) {}

                    @Override
                    public void recordFailure() {
                        failures.incrementAndGet();
                    }

                    @Override
                    public void recordSkipped(String reason) {
                        skipped.incrementAndGet();
                    }
                });

        MunicipalOccupancyRetentionService.Result result = service.cleanup();
        assertThat(result.executed()).isFalse();
        assertThat(result.skipped()).isTrue();
        assertThat(result.skipReason()).isEqualTo("backup_complete_missing");
        assertThat(skipped.get()).isEqualTo(1);
        verify(snapshots, never()).deleteExpiredExcludingLatest(any(), anyInt());
    }

    @Test
    void cleanupIsIdempotentAcrossRuns() {
        MunicipalOccupancySnapshotRepository snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        Instant cutoff = NOW.minus(Duration.ofDays(7));
        when(snapshots.countExpiredExcludingLatest(eq(cutoff))).thenReturn(2L, 0L);
        when(snapshots.deleteExpiredExcludingLatest(eq(cutoff), eq(1000))).thenReturn(2, 0);

        MunicipalOccupancyRetentionService service = new MunicipalOccupancyRetentionService(
                snapshots,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(7),
                1000,
                10,
                false,
                () -> null,
                null);

        MunicipalOccupancyRetentionService.Result first = service.cleanup();
        MunicipalOccupancyRetentionService.Result second = service.cleanup();
        assertThat(first.deletedRows()).isEqualTo(2);
        assertThat(second.deletedRows()).isZero();
        verify(snapshots, times(2)).deleteExpiredExcludingLatest(eq(cutoff), eq(1000));
    }

    @Test
    void respectsMaxBatches() {
        MunicipalOccupancySnapshotRepository snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        Instant cutoff = NOW.minus(Duration.ofDays(7));
        when(snapshots.countExpiredExcludingLatest(eq(cutoff))).thenReturn(5000L);
        when(snapshots.deleteExpiredExcludingLatest(eq(cutoff), eq(1000))).thenReturn(1000);

        MunicipalOccupancyRetentionService service = new MunicipalOccupancyRetentionService(
                snapshots,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(7),
                1000,
                3,
                false,
                () -> null,
                null);

        MunicipalOccupancyRetentionService.Result result = service.cleanup();
        assertThat(result.batches()).isEqualTo(3);
        assertThat(result.deletedRows()).isEqualTo(3000);
        verify(snapshots, times(3)).deleteExpiredExcludingLatest(eq(cutoff), eq(1000));
    }
}
