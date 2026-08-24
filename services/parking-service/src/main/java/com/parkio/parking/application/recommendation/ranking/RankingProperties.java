package com.parkio.parking.application.recommendation.ranking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validated ranking weights and knobs ({@code parkio.spa.ranking.*}).
 *
 * <p>Weights must be finite, non-negative, and sum to ~1.0. Invalid config
 * disables ranking at startup (fail-safe) rather than scoring with bad weights.
 */
@ConfigurationProperties(prefix = "parkio.spa.ranking")
public class RankingProperties implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(RankingProperties.class);

    public static final double WEIGHT_SUM_TOLERANCE = 0.01;

    private boolean enabled = false;
    /** The only production ranking implementation currently supported. */
    private RankingVersion strategy = RankingVersion.DETERMINISTIC_V1;
    private boolean favouritesEnabled = true;
    private double distanceWeight = 0.35;
    private double freshnessWeight = 0.25;
    private double capacityWeight = 0.15;
    private double confidenceWeight = 0.15;
    private double favouriteWeight = 0.10;
    /** Soft walking-distance proxy cap for normalization (meters). */
    private int distanceCapMeters = 1200;
    private boolean configurationValid = true;
    private String configurationError;

    public RankingConfiguration snapshot() {
        return new RankingConfiguration(
                enabled && configurationValid,
                favouritesEnabled,
                distanceWeight,
                freshnessWeight,
                capacityWeight,
                confidenceWeight,
                favouriteWeight,
                distanceCapMeters,
                configurationValid,
                configurationError);
    }

    @Override
    public void afterPropertiesSet() {
        validate();
        if (!configurationValid) {
            log.warn("spa ranking configuration invalid; ranking disabled reason={}", configurationError);
        }
    }

    /** Validate weights. Invalid config disables ranking (fail-safe). */
    public void validate() {
        try {
            if (strategy != RankingVersion.DETERMINISTIC_V1) {
                throw new IllegalStateException("strategy must be DETERMINISTIC_V1");
            }
            requireFiniteNonNegative("distanceWeight", distanceWeight);
            requireFiniteNonNegative("freshnessWeight", freshnessWeight);
            requireFiniteNonNegative("capacityWeight", capacityWeight);
            requireFiniteNonNegative("confidenceWeight", confidenceWeight);
            requireFiniteNonNegative("favouriteWeight", favouriteWeight);
            if (distanceCapMeters < 1) {
                throw new IllegalStateException("distanceCapMeters must be >= 1");
            }
            double sum = distanceWeight + freshnessWeight + capacityWeight
                    + confidenceWeight + favouriteWeight;
            if (Math.abs(sum - 1.0) > WEIGHT_SUM_TOLERANCE) {
                throw new IllegalStateException(
                        "ranking weights must sum to 1.0 ± " + WEIGHT_SUM_TOLERANCE + " (was " + sum + ")");
            }
            configurationValid = true;
            configurationError = null;
        } catch (IllegalStateException ex) {
            configurationValid = false;
            configurationError = ex.getMessage();
            enabled = false;
        }
    }

    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalStateException(name + " must be finite and non-negative");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RankingVersion getStrategy() {
        return strategy;
    }

    public void setStrategy(RankingVersion strategy) {
        this.strategy = strategy;
    }

    public boolean isFavouritesEnabled() {
        return favouritesEnabled;
    }

    public void setFavouritesEnabled(boolean favouritesEnabled) {
        this.favouritesEnabled = favouritesEnabled;
    }

    public double getDistanceWeight() {
        return distanceWeight;
    }

    public void setDistanceWeight(double distanceWeight) {
        this.distanceWeight = distanceWeight;
    }

    public double getFreshnessWeight() {
        return freshnessWeight;
    }

    public void setFreshnessWeight(double freshnessWeight) {
        this.freshnessWeight = freshnessWeight;
    }

    public double getCapacityWeight() {
        return capacityWeight;
    }

    public void setCapacityWeight(double capacityWeight) {
        this.capacityWeight = capacityWeight;
    }

    public double getConfidenceWeight() {
        return confidenceWeight;
    }

    public void setConfidenceWeight(double confidenceWeight) {
        this.confidenceWeight = confidenceWeight;
    }

    public double getFavouriteWeight() {
        return favouriteWeight;
    }

    public void setFavouriteWeight(double favouriteWeight) {
        this.favouriteWeight = favouriteWeight;
    }

    public int getDistanceCapMeters() {
        return distanceCapMeters;
    }

    public void setDistanceCapMeters(int distanceCapMeters) {
        this.distanceCapMeters = distanceCapMeters;
    }

    public boolean isConfigurationValid() {
        return configurationValid;
    }

    public String getConfigurationError() {
        return configurationError;
    }

    /**
     * Immutable snapshot used by the scorer — no Spring types in scoring logic.
     */
    public record RankingConfiguration(
            boolean enabled,
            boolean favouritesEnabled,
            double distanceWeight,
            double freshnessWeight,
            double capacityWeight,
            double confidenceWeight,
            double favouriteWeight,
            int distanceCapMeters,
            boolean configurationValid,
            String configurationError) {

        public RankingConfiguration {
            if (distanceCapMeters < 1) {
                throw new IllegalArgumentException("distanceCapMeters must be >= 1");
            }
        }
    }
}
