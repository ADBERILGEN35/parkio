package com.parkio.parking.application.recommendation.ranking.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShadowComparisonTest {

    @Test
    void identicalOrderIsPerfectAgreement() {
        List<String> order = List.of("c0", "c1", "c2");
        ShadowComparison comparison = ShadowComparison.compare(order, order);
        assertTrue(comparison.top1Agreement());
        assertEquals(3, comparison.top3Overlap());
        assertEquals(1.0, comparison.spearmanRankCorrelation(), 1e-9);
        assertEquals(0.0, comparison.meanAbsoluteRankDelta(), 1e-9);
        assertEquals(0, comparison.maxRankDelta());
    }

    @Test
    void reverseOrderHasLowCorrelation() {
        List<String> auth = List.of("c0", "c1", "c2", "c3");
        List<String> shadow = List.of("c3", "c2", "c1", "c0");
        ShadowComparison comparison = ShadowComparison.compare(auth, shadow);
        assertFalse(comparison.top1Agreement());
        // auth top3 {c0,c1,c2} vs shadow top3 {c3,c2,c1} → overlap c1,c2
        assertEquals(2, comparison.top3Overlap());
        assertTrue(comparison.spearmanRankCorrelation() < 0.0);
        assertTrue(Double.isFinite(comparison.spearmanRankCorrelation()));
        assertEquals(2.0, comparison.meanAbsoluteRankDelta(), 1e-9);
        assertEquals(3, comparison.maxRankDelta());
    }

    @Test
    void singleSwapMetrics() {
        List<String> auth = List.of("c0", "c1", "c2");
        List<String> shadow = List.of("c1", "c0", "c2");
        ShadowComparison comparison = ShadowComparison.compare(auth, shadow);
        assertFalse(comparison.top1Agreement());
        assertEquals(3, comparison.top3Overlap());
        assertEquals(1, comparison.maxRankDelta());
        assertEquals(2.0 / 3.0, comparison.meanAbsoluteRankDelta(), 1e-9);
        assertTrue(comparison.spearmanRankCorrelation() > 0.0);
    }

    @Test
    void singleCandidateCorrelationIsOne() {
        ShadowComparison comparison = ShadowComparison.compare(List.of("c0"), List.of("c0"));
        assertTrue(comparison.top1Agreement());
        assertEquals(1, comparison.top3Overlap());
        assertEquals(1.0, comparison.spearmanRankCorrelation(), 1e-9);
    }
}
