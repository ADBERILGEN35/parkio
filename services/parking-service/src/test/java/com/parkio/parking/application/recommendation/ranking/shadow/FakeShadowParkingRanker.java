package com.parkio.parking.application.recommendation.ranking.shadow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double for {@link ShadowParkingRanker}. */
public final class FakeShadowParkingRanker implements ShadowParkingRanker {

    public enum Mode {
        REVERSE,
        TIMEOUT,
        THROW,
        MALFORMED,
        FIXED,
        IDENTITY
    }

    private final Mode mode;
    private final List<String> fixedOrder;
    private final long timeoutSleepMs;
    private final AtomicInteger invocations = new AtomicInteger();

    public FakeShadowParkingRanker(Mode mode) {
        this(mode, List.of(), 2_000L);
    }

    public FakeShadowParkingRanker(Mode mode, List<String> fixedOrder, long timeoutSleepMs) {
        this.mode = mode;
        this.fixedOrder = List.copyOf(fixedOrder);
        this.timeoutSleepMs = timeoutSleepMs;
    }

    public int invocations() {
        return invocations.get();
    }

    @Override
    public ShadowRankingOutput rank(ShadowRankingRequest request) {
        invocations.incrementAndGet();
        return switch (mode) {
            case REVERSE -> reverse(request);
            case IDENTITY -> identity(request);
            case FIXED -> fixed(request);
            case TIMEOUT -> timeout();
            case THROW -> throw new RuntimeException("fake shadow provider error");
            case MALFORMED -> malformed(request);
        };
    }

    private ShadowRankingOutput reverse(ShadowRankingRequest request) {
        List<String> aliases = new ArrayList<>(request.candidates().stream()
                .map(ShadowCandidateFeatures::alias)
                .toList());
        Collections.reverse(aliases);
        return output(aliases);
    }

    private ShadowRankingOutput identity(ShadowRankingRequest request) {
        List<String> aliases = request.candidates().stream()
                .map(ShadowCandidateFeatures::alias)
                .toList();
        return output(aliases);
    }

    private ShadowRankingOutput fixed(ShadowRankingRequest request) {
        if (fixedOrder.isEmpty()) {
            return identity(request);
        }
        return output(fixedOrder);
    }

    private ShadowRankingOutput timeout() {
        try {
            Thread.sleep(timeoutSleepMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return output(List.of());
    }

    private ShadowRankingOutput malformed(ShadowRankingRequest request) {
        // Duplicate / incomplete cover
        List<String> aliases = request.candidates().stream()
                .map(ShadowCandidateFeatures::alias)
                .toList();
        if (aliases.isEmpty()) {
            return new ShadowRankingOutput(
                    ShadowRankingConstants.OUTPUT_SCHEMA_VERSION,
                    List.of("unknown"),
                    ShadowConfidence.LOW,
                    Map.of());
        }
        return new ShadowRankingOutput(
                ShadowRankingConstants.OUTPUT_SCHEMA_VERSION,
                List.of(aliases.getFirst(), aliases.getFirst()),
                ShadowConfidence.LOW,
                Map.of());
    }

    private static ShadowRankingOutput output(List<String> aliases) {
        Map<String, List<ShadowReasonCategory>> reasons = new HashMap<>();
        for (String alias : aliases) {
            reasons.put(alias, List.of(ShadowReasonCategory.FRESHNESS, ShadowReasonCategory.DISTANCE));
        }
        return new ShadowRankingOutput(
                ShadowRankingConstants.OUTPUT_SCHEMA_VERSION,
                aliases,
                ShadowConfidence.MEDIUM,
                reasons);
    }
}
