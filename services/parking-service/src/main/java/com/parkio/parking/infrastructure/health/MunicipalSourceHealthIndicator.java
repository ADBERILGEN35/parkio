package com.parkio.parking.infrastructure.health;

import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.MunicipalSourceSlaPolicy;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.ispark.IsparkMunicipalParkingAdapter;
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
    private final MunicipalSourceProperties properties;

    public MunicipalSourceHealthIndicator(
            MunicipalSourceHealthService healthService,
            MunicipalSourceProperties properties) {
        this.healthService = healthService;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        try {
            MunicipalSourceHealthService.Snapshot snapshot = healthService.izumSnapshot();
            appendSourceDetails(builder, "izum", snapshot);
            MunicipalSourceHealthService.Snapshot ispark = healthService.snapshot(
                    IsparkMunicipalParkingAdapter.SOURCE_KEY,
                    properties.getIspark().isEnabled(),
                    properties.getIspark().isSchedulerEnabled());
            appendSourceDetails(builder, "ispark", ispark);
            builder.withDetail("municipalEnabled", snapshot.municipalEnabled());
            return builder.build();
        } catch (RuntimeException ex) {
            return builder.withDetail("izumStatus", "probe_error").build();
        }
    }

    private static void appendSourceDetails(
            Health.Builder builder, String prefix, MunicipalSourceHealthService.Snapshot snapshot) {
        MunicipalSourceSlaPolicy.Evaluation evaluation = snapshot.evaluation();
        builder.withDetail(prefix + "Enabled", snapshot.sourceEnabled());
        builder.withDetail(prefix + "SchedulerEnabled", snapshot.schedulerEnabled());
        builder.withDetail(prefix + "SourceMode", snapshot.operatingMode().name());
        builder.withDetail(prefix + "OperationalState", evaluation.operationalState().name());
        builder.withDetail(prefix + "OccupancyFreshness", snapshot.occupancyFreshness().name());
        builder.withDetail(prefix + "ConsecutiveFailures", evaluation.consecutiveFailures());
        builder.withDetail(prefix + "SecondsSinceSuccess", evaluation.secondsSinceSuccess());
        builder.withDetail(prefix + "FailuresInWindow", evaluation.failuresInWindow());
        builder.withDetail(prefix + "StaleRunningOperations", evaluation.staleRunningOperations());
        builder.withDetail(prefix + "Recovered", evaluation.recovered());
        if (evaluation.lastRunStatus() != null) {
            builder.withDetail(prefix + "LastRunStatus", evaluation.lastRunStatus());
        }
        if (evaluation.lastRunAt() != null) {
            builder.withDetail(prefix + "LastRunTimestamp", evaluation.lastRunAt().toString());
        }
        if (evaluation.lastSuccessAt() != null) {
            builder.withDetail(prefix + "LastSuccessTimestamp", evaluation.lastSuccessAt().toString());
            builder.withDetail(prefix + "LastSuccessfulSyncAgeSeconds",
                    Math.max(0, evaluation.secondsSinceSuccess()));
        }
        if (evaluation.lastFailureCategory() != null) {
            builder.withDetail(prefix + "LastErrorCategory", evaluation.lastFailureCategory());
        }
        builder.withDetail(prefix + "Status", mapLegacyStatus(snapshot));
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
