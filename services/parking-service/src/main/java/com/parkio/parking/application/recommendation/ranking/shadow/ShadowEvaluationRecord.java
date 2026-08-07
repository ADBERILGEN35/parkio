package com.parkio.parking.application.recommendation.ranking.shadow;

import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Privacy-safe shadow evaluation artifact for in-memory replay / tests.
 * Contains features, ordinals, comparison, status, versions, and latency only.
 */
public record ShadowEvaluationRecord(
        Instant recordedAt,
        ShadowRankingStatus status,
        RankingVersion authoritativeVersion,
        String featureSchemaVersion,
        String shadowRankerVersion,
        String promptVersion,
        boolean inventoryPartial,
        List<String> authoritativeAliases,
        List<String> shadowAliases,
        ShadowRankingRequest request,
        ShadowRankingOutput output,
        ShadowComparison comparison,
        long latencyMs) {

    public ShadowEvaluationRecord {
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(featureSchemaVersion, "featureSchemaVersion");
        Objects.requireNonNull(shadowRankerVersion, "shadowRankerVersion");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(authoritativeAliases, "authoritativeAliases");
        authoritativeAliases = List.copyOf(authoritativeAliases);
        shadowAliases = shadowAliases == null ? List.of() : List.copyOf(shadowAliases);
        if (latencyMs < 0L) {
            latencyMs = 0L;
        }
    }
}
