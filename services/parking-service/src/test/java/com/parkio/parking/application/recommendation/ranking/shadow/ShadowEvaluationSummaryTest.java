package com.parkio.parking.application.recommendation.ranking.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShadowEvaluationSummaryTest {

    @Test
    void aggregatesSuccessRecords() {
        BoundedShadowEvaluationStore store = new BoundedShadowEvaluationStore();
        store.add(record(ShadowRankingStatus.SUCCESS, true, 1.0));
        store.add(record(ShadowRankingStatus.SUCCESS, false, 0.5));
        store.add(record(ShadowRankingStatus.TIMEOUT, false, 0.0));

        ShadowEvaluationSummary.Summary summary = ShadowEvaluationSummary.summarize(store);
        assertEquals(3, summary.total());
        assertEquals(2, summary.successCount());
        assertEquals(0.5, summary.top1AgreementRate(), 1e-9);
    }

    private static ShadowEvaluationRecord record(
            ShadowRankingStatus status, boolean top1, double spearman) {
        ShadowComparison comparison = status == ShadowRankingStatus.SUCCESS
                ? new ShadowComparison(top1, top1 ? 3 : 1, spearman, 0.5, 1)
                : null;
        return new ShadowEvaluationRecord(
                Instant.parse("2026-08-07T12:00:00Z"),
                status,
                RankingVersion.DETERMINISTIC_V1,
                ShadowRankingConstants.FEATURE_SCHEMA_VERSION,
                ShadowRankingConstants.SHADOW_RANKER_VERSION,
                ShadowRankingConstants.PROMPT_VERSION,
                false,
                List.of("c0", "c1"),
                List.of("c0", "c1"),
                null,
                null,
                comparison,
                10L);
    }
}
