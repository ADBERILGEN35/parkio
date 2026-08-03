package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.externalsource.registry.MunicipalQualityReportPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Bounded operator quality-report counters (DATA-WP-15).
 * Allowed tags only: report_type, outcome, source_family, policy_version.
 * Full source keys are never used as tags to keep cardinality fixed.
 */
@Component
public class MunicipalQualityReportMetrics {
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_CLIENT_ERROR = "client_error";
    public static final String OUTCOME_NOT_FOUND = "not_found";
    public static final String OUTCOME_ERROR = "error";

    public static final String TYPE_OVERALL = "overall";
    public static final String TYPE_SOURCE = "source";

    public static final String FAMILY_NONE = "none";

    private final MeterRegistry registry;

    public MunicipalQualityReportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String reportType, String outcome, String sourceFamily, long durationMs) {
        registry.counter(
                "parkio.municipal.ops.quality_report",
                "report_type", reportType,
                "outcome", outcome,
                "source_family", sourceFamily,
                "policy_version", MunicipalQualityReportPolicy.POLICY_VERSION).increment();
        registry.timer(
                "parkio.municipal.ops.quality_report.duration",
                "report_type", reportType,
                "outcome", outcome,
                "source_family", sourceFamily,
                "policy_version", MunicipalQualityReportPolicy.POLICY_VERSION)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
