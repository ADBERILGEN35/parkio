package com.parkio.parking.infrastructure.health;

import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.MunicipalSourceSlaPolicy;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Non-critical municipal source health. Overall status stays UP so liveness is not
 * blocked. Details expose bounded operational SLA and occupancy freshness fields.
 */
@Component("municipalSources")
public class MunicipalSourceHealthIndicator implements HealthIndicator {
    private final MunicipalSourceHealthService healthService;

    public MunicipalSourceHealthIndicator(MunicipalSourceHealthService healthService) {
        this.healthService = healthService;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        try {
            MunicipalSourceHealthService.Snapshot snapshot = healthService.izumSnapshot();
            MunicipalSourceSlaPolicy.Evaluation evaluation = snapshot.evaluation();
            builder.withDetail("municipalEnabled", snapshot.municipalEnabled());
            builder.withDetail("izumEnabled", snapshot.sourceEnabled());
            builder.withDetail("izumSchedulerEnabled", snapshot.schedulerEnabled());
            builder.withDetail("izumOperationalState", evaluation.operationalState().name());
            builder.withDetail("izumOccupancyFreshness", snapshot.occupancyFreshness().name());
            builder.withDetail("izumConsecutiveFailures", evaluation.consecutiveFailures());
            builder.withDetail("izumSecondsSinceSuccess", evaluation.secondsSinceSuccess());
            builder.withDetail("izumFailuresInWindow", evaluation.failuresInWindow());
            builder.withDetail("izumStaleRunningOperations", evaluation.staleRunningOperations());
            builder.withDetail("izumRecovered", evaluation.recovered());
            if (evaluation.lastRunStatus() != null) {
                builder.withDetail("izumLastRunStatus", evaluation.lastRunStatus());
            }
            if (evaluation.lastRunAt() != null) {
                builder.withDetail("izumLastRunTimestamp", evaluation.lastRunAt().toString());
            }
            if (evaluation.lastSuccessAt() != null) {
                builder.withDetail("izumLastSuccessTimestamp", evaluation.lastSuccessAt().toString());
                builder.withDetail("izumLastSuccessfulSyncAgeSeconds",
                        Math.max(0, evaluation.secondsSinceSuccess()));
            }
            if (evaluation.lastFailureCategory() != null) {
                builder.withDetail("izumLastErrorCategory", evaluation.lastFailureCategory());
            }
            builder.withDetail("izumStatus", mapLegacyStatus(snapshot));
            return builder.build();
        } catch (RuntimeException ex) {
            return builder.withDetail("izumStatus", "probe_error").build();
        }
    }

    private static String mapLegacyStatus(MunicipalSourceHealthService.Snapshot snapshot) {
        return switch (snapshot.operationalState()) {
            case DISABLED -> "disabled";
            case NEVER_RUN -> "never_synced";
            case HEALTHY, RECOVERING -> switch (snapshot.occupancyFreshness()) {
                case LIVE -> "healthy";
                case AGING -> "aging";
                case STALE -> "stale";
                default -> "healthy";
            };
            case DEGRADED, CRITICAL, STALE_OPERATION -> {
                if (MunicipalSourceFailureCategoryCompat.isSchema(
                        snapshot.evaluation().lastFailureCategory())) {
                    yield "schema_mismatch";
                }
                yield "failing";
            }
            case UNKNOWN -> "probe_error";
        };
    }

    /** Tiny local helper to avoid importing failure category into health package cycles. */
    private static final class MunicipalSourceFailureCategoryCompat {
        private static boolean isSchema(String wire) {
            return "schema_contract".equals(wire) || "contract".equals(wire);
        }
    }
}
