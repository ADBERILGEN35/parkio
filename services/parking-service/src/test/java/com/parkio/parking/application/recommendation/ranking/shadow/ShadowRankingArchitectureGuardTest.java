package com.parkio.parking.application.recommendation.ranking.shadow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.parkio.parking.application.recommendation.ranking.DeterministicParkingCandidateRanker;
import com.parkio.parking.application.recommendation.ranking.ParkingCandidateRanker;
import org.junit.jupiter.api.Test;

class ShadowRankingArchitectureGuardTest {

    @Test
    void deterministicImplementsPublicRankerLocalChallengerDoesNot() {
        assertTrue(ParkingCandidateRanker.class.isAssignableFrom(DeterministicParkingCandidateRanker.class));
        assertFalse(ParkingCandidateRanker.class.isAssignableFrom(LocalChallengerShadowParkingRanker.class));
        assertTrue(ShadowParkingRanker.class.isAssignableFrom(LocalChallengerShadowParkingRanker.class));
        assertFalse(ShadowParkingRanker.class.isAssignableFrom(DeterministicParkingCandidateRanker.class));
    }
}
