package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.MunicipalOccupancyRetentionService;
import com.parkio.parking.application.MunicipalOccupancyRetentionService.Preview;
import com.parkio.parking.application.MunicipalOccupancyRetentionService.Result;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.infrastructure.metrics.MunicipalOccupancyRetentionMetrics;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fail-closed municipal occupancy retention scheduler. Default disabled.
 * When enabled, runs after the daily backup window (cron default 04:00 UTC).
 */
@Component
public class MunicipalOccupancyRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(MunicipalOccupancyRetentionJob.class);

    private final boolean enabled;
    private final boolean runOnStartup;
    private final MunicipalOccupancyRetentionService service;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MunicipalOccupancyRetentionJob(
            MunicipalOccupancySnapshotRepository snapshots,
            Clock clock,
            MunicipalOccupancyRetentionMetrics metrics,
            @Value("${parkio.lifecycle.municipal-occupancy-retention.enabled:false}") boolean enabled,
            @Value("${parkio.lifecycle.municipal-occupancy-retention.run-on-startup:false}") boolean runOnStartup,
            @Value("${parkio.lifecycle.municipal-occupancy-retention.retention:P7D}") Duration retention,
            @Value("${parkio.lifecycle.municipal-occupancy-retention.batch-size:1000}") int batchSize,
            @Value("${parkio.lifecycle.municipal-occupancy-retention.max-batches-per-run:500}")
                    int maxBatchesPerRun,
            @Value("${parkio.lifecycle.municipal-occupancy-retention.require-backup-gate:true}")
                    boolean requireBackupGate,
            @Value("${parkio.lifecycle.municipal-occupancy-retention.backup-stamp-dir:/var/backups/parkio}")
                    String backupStampDir,
            @Value("${parkio.lifecycle.municipal-occupancy-retention.backup-max-age:PT36H}")
                    Duration backupMaxAge) {
        this.enabled = enabled;
        this.runOnStartup = runOnStartup;
        MunicipalOccupancyRetentionService.BackupGate gate = requireBackupGate
                ? new MunicipalOccupancyRetentionBackupGate(Path.of(backupStampDir), backupMaxAge, clock)
                : () -> null;
        this.service = new MunicipalOccupancyRetentionService(
                snapshots,
                clock,
                retention,
                batchSize,
                maxBatchesPerRun,
                requireBackupGate,
                gate,
                metrics);
    }

    @jakarta.annotation.PostConstruct
    void maybeRunOnStartup() {
        if (enabled && runOnStartup) {
            runRetention("startup");
        }
    }

    @Scheduled(cron = "${parkio.lifecycle.municipal-occupancy-retention.cron:0 0 4 * * *}")
    public void scheduledCleanup() {
        runRetention("cron");
    }

    /** Preview aggregates only — safe for dry-run / ops. */
    public Preview preview() {
        return service.preview();
    }

    /** Explicit one-shot for controlled production activation. */
    public Result runOnce() {
        return runRetention("manual");
    }

    private Result runRetention(String trigger) {
        if (!enabled) {
            log.debug("municipal occupancy retention skipped (disabled) trigger={}", trigger);
            return new Result(false, true, "disabled", null, 0, 0, 0, 0);
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("municipal occupancy retention skipped (overlap) trigger={}", trigger);
            return new Result(false, true, "overlap", null, 0, 0, 0, 0);
        }
        try {
            Preview preview = service.preview();
            log.info(
                    "municipal occupancy retention start trigger={} total={} eligible={} cutoff={}",
                    trigger,
                    preview.totalRows(),
                    preview.eligibleRows(),
                    preview.cutoff());
            Result result = service.cleanup();
            if (result.skipped()) {
                log.warn(
                        "municipal occupancy retention skipped trigger={} reason={}",
                        trigger,
                        result.skipReason());
            } else {
                log.info(
                        "municipal occupancy retention done trigger={} deleted={} batches={} eligibleBefore={} durationMs={}",
                        trigger,
                        result.deletedRows(),
                        result.batches(),
                        result.eligibleBefore(),
                        result.durationMs());
            }
            return result;
        } catch (RuntimeException ex) {
            log.error("municipal occupancy retention failed trigger={}", trigger, ex);
            return new Result(false, true, "error", null, 0, 0, 0, 0);
        } finally {
            running.set(false);
        }
    }
}
