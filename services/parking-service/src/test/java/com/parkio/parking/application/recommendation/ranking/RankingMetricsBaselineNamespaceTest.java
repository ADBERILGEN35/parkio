package com.parkio.parking.application.recommendation.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RankingMetricsBaselineNamespaceTest {

    private SimpleMeterRegistry registry;
    private RankingMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RankingMetrics(registry);
    }

    @Test
    void recordShadowUsesBaselineNamespaceNotShadow() {
        metrics.recordShadow(true, 2);

        assertEquals(
                1.0,
                registry.counter("parkio.spa.ranking.baseline.top1_changed", "changed", "true").count());
        assertEquals(
                1.0,
                registry.counter("parkio.spa.ranking.baseline.top3_overlap", "overlap", "2").count());

        assertNull(
                registry.find("parkio.spa.ranking.shadow.top1_changed").meter(),
                "SPA-06 baseline must not write shadow.* top1_changed");
        assertNull(
                registry.find("parkio.spa.ranking.shadow.top3_overlap").meter(),
                "SPA-06 baseline must not write shadow.* top3_overlap");

        long shadowMeters = registry.getMeters().stream()
                .map(Meter::getId)
                .map(Meter.Id::getName)
                .filter(name -> name.startsWith("parkio.spa.ranking.shadow."))
                .count();
        assertEquals(0L, shadowMeters);
    }
}
