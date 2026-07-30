package com.parkio.parking.infrastructure.health;

import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Non-critical municipal source health. Overall status stays UP so liveness is not
 * blocked, while details distinguish disabled / never synced / live / aging / stale /
 * failing / schema_mismatch.
 */
@Component("municipalSources")
public class MunicipalSourceHealthIndicator implements HealthIndicator {
    private final MunicipalSourceProperties properties;
    private final MunicipalDataSourceRepository sources;
    private final MunicipalSourceSyncRunRepository runs;
    private final Clock clock;

    public MunicipalSourceHealthIndicator(
            MunicipalSourceProperties properties,
            MunicipalDataSourceRepository sources,
            MunicipalSourceSyncRunRepository runs,
            Clock clock) {
        this.properties = properties;
        this.sources = sources;
        this.runs = runs;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        builder.withDetail("municipalEnabled", properties.isEnabled());
        builder.withDetail("izumEnabled", properties.getIzum().isEnabled());
        if (!properties.isEnabled() || !properties.getIzum().isEnabled()) {
            builder.withDetail("izumStatus", "disabled");
            return builder.build();
        }
        try {
            Optional<MunicipalDataSourceRepository.Source> source =
                    sources.findBySourceKey(IzumMunicipalParkingAdapter.SOURCE_KEY);
            if (source.isEmpty()) {
                builder.withDetail("izumStatus", "source_missing");
                return builder.build();
            }
            MunicipalDataSourceRepository.Source value = source.get();
            Optional<MunicipalSourceSyncRunRepository.LatestRun> latest = runs.findLatestCompleted(value.id());
            if (latest.isPresent()) {
                MunicipalSourceSyncRunRepository.LatestRun run = latest.get();
                builder.withDetail("izumLastRunStatus", run.status());
                if ("FAILED".equals(run.status())) {
                    if ("contract".equals(run.errorCategory())) {
                        builder.withDetail("izumStatus", "schema_mismatch");
                    } else {
                        builder.withDetail("izumStatus", "failing");
                    }
                    return builder.build();
                }
            }
            Instant last = value.lastSuccessfulSyncAt();
            if (last == null) {
                builder.withDetail("izumStatus", "never_synced");
                return builder.build();
            }
            long ageSeconds = Duration.between(last, clock.instant()).getSeconds();
            builder.withDetail("izumLastSuccessfulSyncAgeSeconds", Math.max(0, ageSeconds));
            if (ageSeconds >= value.staleAfterSeconds()) {
                builder.withDetail("izumStatus", "stale");
            } else if (ageSeconds >= value.agingAfterSeconds()) {
                builder.withDetail("izumStatus", "aging");
            } else {
                builder.withDetail("izumStatus", "healthy");
            }
            return builder.build();
        } catch (RuntimeException ex) {
            return builder.withDetail("izumStatus", "probe_error").build();
        }
    }
}