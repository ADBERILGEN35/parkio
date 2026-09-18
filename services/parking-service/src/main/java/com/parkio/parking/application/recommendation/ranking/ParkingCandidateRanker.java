package com.parkio.parking.application.recommendation.ranking;

import com.parkio.parking.application.recommendation.ParkingCandidate;
import java.util.List;

/** Ranks recommendation candidates. Does not fetch inventory or favourites. */
public interface ParkingCandidateRanker {

    RankingOutcome rank(RankingContext context);

    record RankingOutcome(
            List<ParkingCandidate> ranked,
            RankingVersion version,
            RankingStatus status) {}
}
