package com.parkio.parking.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Bounded DATA-WP-18 district coverage metrics. District names are never metric labels.
 */
@Component
public class MunicipalDistrictCoverageMetrics {
    private final MeterRegistry registry;

    public MunicipalDistrictCoverageMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRequest(
            String outcome,
            String assetStatus,
            String policyVersion,
            long durationMs,
            long facilities,
            long anomalies) {
        registry.counter(
                        "parkio.municipal.ops.district_coverage.requests",
                        "outcome",
                        sanitize(outcome),
                        "asset_status",
                        sanitize(assetStatus),
                        "policy_version",
                        sanitize(policyVersion))
                .increment();
        Timer.builder("parkio.municipal.ops.district_coverage.duration")
                .tags(
                        "outcome",
                        sanitize(outcome),
                        "asset_status",
                        sanitize(assetStatus),
                        "policy_version",
                        sanitize(policyVersion))
                .register(registry)
                .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
        registry.counter(
                        "parkio.municipal.ops.district_coverage.facilities",
                        "outcome",
                        sanitize(outcome))
                .increment(Math.max(0, facilities));
        if (anomalies > 0) {
            registry.counter(
                            "parkio.municipal.ops.district_coverage.anomalies",
                            "anomaly_type",
                            "overlap",
                            "outcome",
                            sanitize(outcome))
                    .increment(anomalies);
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
