package com.parkio.parking.application.recommendation.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RankingPropertiesTest {

    @Test
    void defaultWeightsAreValidAndSumToOne() {
        RankingProperties props = new RankingProperties();
        props.validate();
        assertTrue(props.isConfigurationValid());
        assertFalse(props.isEnabled());
        RankingProperties.RankingConfiguration snap = props.snapshot();
        assertFalse(snap.enabled());
        double sum = snap.distanceWeight()
                + snap.freshnessWeight()
                + snap.capacityWeight()
                + snap.confidenceWeight()
                + snap.favouriteWeight();
        assertEquals(1.0, sum, RankingProperties.WEIGHT_SUM_TOLERANCE);
    }

    @Test
    void negativeWeightDisablesRanking() {
        RankingProperties props = new RankingProperties();
        props.setEnabled(true);
        props.setDistanceWeight(-0.1);
        props.validate();
        assertFalse(props.isConfigurationValid());
        assertFalse(props.isEnabled());
        assertFalse(props.snapshot().enabled());
    }

    @Test
    void nanWeightDisablesRanking() {
        RankingProperties props = new RankingProperties();
        props.setEnabled(true);
        props.setFreshnessWeight(Double.NaN);
        props.validate();
        assertFalse(props.isConfigurationValid());
        assertFalse(props.isEnabled());
    }

    @Test
    void infiniteWeightDisablesRanking() {
        RankingProperties props = new RankingProperties();
        props.setEnabled(true);
        props.setCapacityWeight(Double.POSITIVE_INFINITY);
        props.validate();
        assertFalse(props.isConfigurationValid());
    }

    @Test
    void sumNotOneDisablesRanking() {
        RankingProperties props = new RankingProperties();
        props.setEnabled(true);
        props.setDistanceWeight(0.5);
        props.setFreshnessWeight(0.5);
        props.setCapacityWeight(0.5);
        props.setConfidenceWeight(0.0);
        props.setFavouriteWeight(0.0);
        props.validate();
        assertFalse(props.isConfigurationValid());
        assertFalse(props.isEnabled());
    }

    @Test
    void zeroWeightsAllowedWhenSumIsOne() {
        RankingProperties props = new RankingProperties();
        props.setEnabled(true);
        props.setDistanceWeight(1.0);
        props.setFreshnessWeight(0.0);
        props.setCapacityWeight(0.0);
        props.setConfidenceWeight(0.0);
        props.setFavouriteWeight(0.0);
        props.validate();
        assertTrue(props.isConfigurationValid());
        assertTrue(props.snapshot().enabled());
    }

    @Test
    void invalidDistanceCapDisablesRanking() {
        RankingProperties props = new RankingProperties();
        props.setEnabled(true);
        props.setDistanceCapMeters(0);
        props.validate();
        assertFalse(props.isConfigurationValid());
        assertFalse(props.isEnabled());
    }
}
