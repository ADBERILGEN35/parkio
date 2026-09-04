package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.registry.MunicipalQualityReportPolicy;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DATA-WP-15: quality-report counters must stay at fixed cardinality. Full source keys,
 * facility ids and error text are never tags.
 */
class MunicipalQualityReportMetricsTest {
    private static final String COUNTER = "parkio.municipal.ops.quality_report";
    private static final String TIMER = "parkio.municipal.ops.quality_report.duration";
    private static final Set<String> ALLOWED_TAGS =
            Set.of("report_type", "outcome", "source_family", "policy_version");

    private SimpleMeterRegistry registry;
    private MunicipalQualityReportMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MunicipalQualityReportMetrics(registry);
    }

    @Test
    void recordsOnlyAllowedTagKeys() {
        metrics.record(
                MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                MunicipalSourceIdentity.FAMILY_OSM,
                12L);

        assertThat(registry.getMeters()).isNotEmpty();
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags().stream().map(Tag::getKey))
                    .allSatisfy(key -> assertThat(ALLOWED_TAGS).contains(key));
            assertThat(meter.getId().getTags()).hasSize(ALLOWED_TAGS.size());
        }
    }

    @Test
    void tagsNeverCarryTheFullSourceKey() {
        metrics.record(
                MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                MunicipalSourceIdentity.familyOf(MunicipalSourceIdentity.OSM),
                5L);
        metrics.record(
                MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                MunicipalSourceIdentity.familyOf(MunicipalSourceIdentity.IZUM),
                5L);

        String joined = registry.getMeters().stream()
                .map(meter -> meter.getId().toString())
                .collect(Collectors.joining(","));
        assertThat(joined)
                .doesNotContain(MunicipalSourceIdentity.OSM)
                .doesNotContain(MunicipalSourceIdentity.IZUM)
                .doesNotContain("osm-geofabrik-turkey")
                .doesNotContain("izmir-izum-otoparklar");

        List<String> families = registry.find(COUNTER).counters().stream()
                .map(counter -> counter.getId().getTag("source_family"))
                .sorted()
                .toList();
        assertThat(families).containsExactly(
                MunicipalSourceIdentity.FAMILY_IZUM, MunicipalSourceIdentity.FAMILY_OSM);
    }

    @Test
    void everyMeterIsStampedWithThePolicyVersion() {
        metrics.record(
                MunicipalQualityReportMetrics.TYPE_OVERALL,
                MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                MunicipalQualityReportMetrics.FAMILY_NONE,
                3L);

        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTag("policy_version"))
                        .isEqualTo(MunicipalQualityReportPolicy.POLICY_VERSION));
    }

    @Test
    void distinctOutcomesProduceDistinctBoundedSeries() {
        metrics.record(MunicipalQualityReportMetrics.TYPE_OVERALL,
                MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                MunicipalQualityReportMetrics.FAMILY_NONE, 1L);
        metrics.record(MunicipalQualityReportMetrics.TYPE_OVERALL,
                MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                MunicipalQualityReportMetrics.FAMILY_NONE, 2L);
        metrics.record(MunicipalQualityReportMetrics.TYPE_OVERALL,
                MunicipalQualityReportMetrics.OUTCOME_ERROR,
                MunicipalQualityReportMetrics.FAMILY_NONE, 3L);
        metrics.record(MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_NOT_FOUND,
                MunicipalSourceIdentity.FAMILY_UNKNOWN, 4L);
        metrics.record(MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_CLIENT_ERROR,
                MunicipalSourceIdentity.FAMILY_OSM, 5L);

        assertThat(registry.find(COUNTER)
                        .tag("report_type", MunicipalQualityReportMetrics.TYPE_OVERALL)
                        .tag("outcome", MunicipalQualityReportMetrics.OUTCOME_SUCCESS)
                        .counter())
                .isNotNull()
                .extracting(io.micrometer.core.instrument.Counter::count)
                .isEqualTo(2.0);
        assertThat(registry.find(COUNTER).counters()).hasSize(4);
        assertThat(registry.find(TIMER).timers()).hasSize(4);
        assertThat(registry.find(TIMER)
                        .tag("outcome", MunicipalQualityReportMetrics.OUTCOME_NOT_FOUND)
                        .timer()
                        .count())
                .isEqualTo(1L);
    }

    @Test
    void outcomeAndTypeConstantsStayLowercaseAndBounded() {
        assertThat(List.of(
                        MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                        MunicipalQualityReportMetrics.OUTCOME_CLIENT_ERROR,
                        MunicipalQualityReportMetrics.OUTCOME_NOT_FOUND,
                        MunicipalQualityReportMetrics.OUTCOME_ERROR,
                        MunicipalQualityReportMetrics.TYPE_OVERALL,
                        MunicipalQualityReportMetrics.TYPE_SOURCE,
                        MunicipalQualityReportMetrics.FAMILY_NONE))
                .allSatisfy(value -> assertThat(value).isEqualTo(value.toLowerCase(java.util.Locale.ROOT)))
                .doesNotHaveDuplicates();
    }
}
