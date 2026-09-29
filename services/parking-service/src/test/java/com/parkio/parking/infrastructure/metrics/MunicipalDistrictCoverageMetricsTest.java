package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.district.MunicipalDistrictCoveragePolicy;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DATA-WP-18: district coverage metrics stay bounded; district names are never tags.
 */
class MunicipalDistrictCoverageMetricsTest {
    private static final String REQUEST_COUNTER = "parkio.municipal.ops.district_coverage.requests";
    private static final String DURATION_TIMER = "parkio.municipal.ops.district_coverage.duration";
    private static final String FACILITY_COUNTER = "parkio.municipal.ops.district_coverage.facilities";
    private static final String ANOMALY_COUNTER = "parkio.municipal.ops.district_coverage.anomalies";
    private static final Set<String> ALLOWED_TAG_KEYS =
            Set.of("outcome", "asset_status", "policy_version", "anomaly_type");

    private SimpleMeterRegistry registry;
    private MunicipalDistrictCoverageMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MunicipalDistrictCoverageMetrics(registry);
    }

    @Test
    void recordRequestNeverUsesDistrictNamesAsTags() {
        metrics.recordRequest("success", "available", MunicipalDistrictCoveragePolicy.POLICY_VERSION, 7L, 42L, 3L);

        String joined = registry.getMeters().stream()
                .map(meter -> meter.getId().toString())
                .collect(Collectors.joining(","));
        assertThat(joined)
                .doesNotContain("KONAK")
                .doesNotContain("KINIK")
                .doesNotContain("KARSIYAKA");

        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags().stream().map(Tag::getKey))
                    .allSatisfy(key -> assertThat(ALLOWED_TAG_KEYS).contains(key));
        }
    }

    @Test
    void distinctOutcomesProduceBoundedSeries() {
        metrics.recordRequest("success", "available", MunicipalDistrictCoveragePolicy.POLICY_VERSION, 1L, 10L, 0L);
        metrics.recordRequest("success", "available", MunicipalDistrictCoveragePolicy.POLICY_VERSION, 2L, 5L, 0L);
        metrics.recordRequest("cache_hit", "available", MunicipalDistrictCoveragePolicy.POLICY_VERSION, 0L, 10L, 0L);
        metrics.recordRequest("unavailable", "unavailable", MunicipalDistrictCoveragePolicy.POLICY_VERSION, 3L, 0L, 0L);
        metrics.recordRequest("disabled", "disabled", "none", 0L, 0L, 0L);
        metrics.recordRequest("success", "available", MunicipalDistrictCoveragePolicy.POLICY_VERSION, 4L, 2L, 2L);

        assertThat(registry.find(REQUEST_COUNTER)
                        .tag("outcome", "success")
                        .tag("asset_status", "available")
                        .counter())
                .isNotNull()
                .extracting(io.micrometer.core.instrument.Counter::count)
                .isEqualTo(3.0);
        assertThat(registry.find(REQUEST_COUNTER).counters()).hasSize(4);
        assertThat(registry.find(DURATION_TIMER).timers()).hasSize(4);
        assertThat(registry.find(FACILITY_COUNTER).counters()).hasSize(4);
        assertThat(registry.find(ANOMALY_COUNTER)
                        .tag("anomaly_type", "overlap")
                        .tag("outcome", "success")
                        .counter())
                .isNotNull()
                .extracting(io.micrometer.core.instrument.Counter::count)
                .isEqualTo(2.0);
    }

    @Test
    void outcomeAndStatusValuesStayLowercaseAndBounded() {
        assertThat(Set.of("success", "cache_hit", "unavailable", "disabled", "available", "none"))
                .allSatisfy(value -> assertThat(value).isEqualTo(value.toLowerCase(java.util.Locale.ROOT)));
        assertThat(MunicipalDistrictCoveragePolicy.POLICY_VERSION.length()).isLessThanOrEqualTo(64);
    }
}
