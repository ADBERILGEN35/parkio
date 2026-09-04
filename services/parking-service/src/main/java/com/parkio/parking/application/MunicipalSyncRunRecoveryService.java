package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository.RecoveredRunView;
import com.parkio.parking.externalsource.MunicipalSourceFailureCategory;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import com.parkio.parking.infrastructure.persistence.MunicipalSourceSyncRunRepositoryAdapter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider-neutral stale RUNNING recovery (MUNI-SYNC-RESILIENCE-01).
 *
 * <p>Mutates only sync-run control rows. Never touches facilities, links, or occupancy.
 */
public class MunicipalSyncRunRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(MunicipalSyncRunRecoveryService.class);

    private final MunicipalSourceSyncRunRepository runs;
    private final MunicipalSourceProperties properties;
    private final MunicipalSourceMetrics metrics;
    private final Clock clock;

    public MunicipalSyncRunRecoveryService(
            MunicipalSourceSyncRunRepository runs,
            MunicipalSourceProperties properties,
            MunicipalSourceMetrics metrics,
            Clock clock) {
        this.runs = runs;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Recovers all stale RUNNING rows across municipal sources. Idempotent; safe with zero rows.
     *
     * @return number of rows terminalized
     */
    public int recoverStaleRunning() {
        MunicipalSourceProperties.Sync sync = properties.getSync();
        if (!sync.isStaleRunRecoveryEnabled()) {
            return 0;
        }
        Instant completedAt = clock.instant();
        Instant olderThan = completedAt.minus(sync.getStaleRunningThreshold());
        try {
            List<RecoveredRunView> recovered = runs.recoverStaleRunning(olderThan, completedAt);
            if (!recovered.isEmpty()) {
                metrics.recordStaleDetected(recovered.size());
            }
            for (RecoveredRunView row : recovered) {
                metrics.recordStaleRecovered(row.sourceKey());
                log.info(
                        "municipal_sync_stale_recovered source={} ageBucket={} status=FAILED category={}",
                        row.sourceKey(),
                        MunicipalSourceSyncRunRepositoryAdapter.ageBucket(row.startedAt(), completedAt),
                        MunicipalSourceFailureCategory.STALE_RUN_RECOVERED.wireValue());
            }
            if (!recovered.isEmpty()) {
                log.info("municipal_sync_stale_recovery_pass recovered={}", recovered.size());
            }
            return recovered.size();
        } catch (RuntimeException ex) {
            metrics.recordStaleRecoveryFailed();
            log.warn("municipal_sync_stale_recovery_failed reason={}", ex.getClass().getSimpleName());
            return 0;
        }
    }
}
