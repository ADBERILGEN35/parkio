package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.MunicipalSyncRunRecoveryService;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Clears orphan RUNNING sync locks before scheduled municipal jobs can wedge on
 * {@code concurrent_run}. Runs early; never fails startup when zero rows match.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "parkio.municipal.sync", name = "stale-run-recovery-enabled", havingValue = "true",
        matchIfMissing = true)
public class MunicipalSyncStaleRunRecoveryStartup implements ApplicationRunner {
    private final MunicipalSyncRunRecoveryService recovery;
    private final MunicipalSourceProperties properties;

    public MunicipalSyncStaleRunRecoveryStartup(
            MunicipalSyncRunRecoveryService recovery, MunicipalSourceProperties properties) {
        this.recovery = recovery;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getSync().isStaleRunRecoveryEnabled()) {
            return;
        }
        recovery.recoverStaleRunning();
    }
}
