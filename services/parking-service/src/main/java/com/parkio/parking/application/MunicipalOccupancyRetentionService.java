package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Bounded municipal occupancy retention: delete raw snapshots older than the
 * configured window while always preserving the latest row per
 * {@code (facility_id, source_id)}.
 */
public class MunicipalOccupancyRetentionService {

    public record Preview(Instant cutoff, long totalRows, long eligibleRows) {}

    public record Result(
            boolean executed,
            boolean skipped,
            String skipReason,
            Instant cutoff,
            long eligibleBefore,
            int deletedRows,
            int batches,
            long durationMs) {}

    public interface BackupGate {
        /** @return empty when backup health is acceptable; otherwise a skip reason */
        String denyReason();
    }

    public interface Metrics {
        void recordSuccess(int deletedRows, long durationMs, Instant successAt);

        void recordFailure();

        void recordSkipped(String reason);
    }

    private final MunicipalOccupancySnapshotRepository snapshots;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final boolean requireBackupGate;
    private final BackupGate backupGate;
    private final Metrics metrics;

    public MunicipalOccupancyRetentionService(
            MunicipalOccupancySnapshotRepository snapshots,
            Clock clock,
            Duration retention,
            int batchSize,
            int maxBatchesPerRun,
            boolean requireBackupGate,
            BackupGate backupGate,
            Metrics metrics) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.clock = Objects.requireNonNull(clock);
        this.retention = retention == null ? Duration.ofDays(7) : retention;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatchesPerRun = Math.max(1, maxBatchesPerRun);
        this.requireBackupGate = requireBackupGate;
        this.backupGate = backupGate == null ? () -> null : backupGate;
        this.metrics = metrics == null ? NoopMetrics.INSTANCE : metrics;
    }

    public Preview preview() {
        Instant cutoff = clock.instant().minus(retention);
        return new Preview(cutoff, snapshots.count(), snapshots.countExpiredExcludingLatest(cutoff));
    }

    public Result cleanup() {
        Instant started = clock.instant();
        if (requireBackupGate) {
            String deny = backupGate.denyReason();
            if (deny != null && !deny.isBlank()) {
                metrics.recordSkipped(deny);
                return new Result(false, true, deny, null, 0, 0, 0, 0);
            }
        }
        Instant cutoff = started.minus(retention);
        long eligible = snapshots.countExpiredExcludingLatest(cutoff);
        int deleted = 0;
        int batches = 0;
        try {
            while (batches < maxBatchesPerRun) {
                int batch = snapshots.deleteExpiredExcludingLatest(cutoff, batchSize);
                if (batch <= 0) {
                    break;
                }
                deleted += batch;
                batches++;
                if (batch < batchSize) {
                    break;
                }
            }
            long durationMs = Math.max(0, Duration.between(started, clock.instant()).toMillis());
            metrics.recordSuccess(deleted, durationMs, clock.instant());
            return new Result(true, false, null, cutoff, eligible, deleted, batches, durationMs);
        } catch (RuntimeException ex) {
            metrics.recordFailure();
            throw ex;
        }
    }

    private enum NoopMetrics implements Metrics {
        INSTANCE;

        @Override
        public void recordSuccess(int deletedRows, long durationMs, Instant successAt) {}

        @Override
        public void recordFailure() {}

        @Override
        public void recordSkipped(String reason) {}
    }
}
