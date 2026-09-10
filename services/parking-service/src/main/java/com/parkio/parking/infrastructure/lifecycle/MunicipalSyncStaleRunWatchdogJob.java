package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.MunicipalSyncRunRecoveryService;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic stale RUNNING recovery for wedged workers without process restart.
 * Overlap-guarded; recovery itself is idempotent.
 */
@Component
@ConditionalOnProperty(prefix = "parkio.municipal.sync", name = "stale-run-watchdog-enabled", havingValue = "true",
        matchIfMissing = true)
public class MunicipalSyncStaleRunWatchdogJob {
    private final MunicipalSyncRunRecoveryService recovery;
    private final MunicipalSourceProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MunicipalSyncStaleRunWatchdogJob(
            MunicipalSyncRunRecoveryService recovery, MunicipalSourceProperties properties) {
        this.recovery = recovery;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${parkio.municipal.sync.stale-run-watchdog-fixed-delay-ms:120000}")
    public void recover() {
        if (!properties.getSync().isStaleRunRecoveryEnabled()
                || !properties.getSync().isStaleRunWatchdogEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            recovery.recoverStaleRunning();
        } finally {
            running.set(false);
        }
    }
}
