package com.parkio.parking.application.recommendation.ranking.shadow;

/**
 * Separate from {@link com.parkio.parking.application.recommendation.ranking.ParkingCandidateRanker}.
 * Never authoritative; never {@code @Primary} over the deterministic public ranker.
 * May throw; timeout is enforced by the orchestrator.
 */
public interface ShadowParkingRanker {

    ShadowRankingOutput rank(ShadowRankingRequest request);
}
