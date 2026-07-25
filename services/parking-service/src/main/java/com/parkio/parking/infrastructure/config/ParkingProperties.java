package com.parkio.parking.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code parkio.parking.*}: tunable nearby-search and moderation-lifecycle settings. */
@ConfigurationProperties(prefix = "parkio.parking")
public class ParkingProperties {

    private Search search = new Search();
    private Moderation moderation = new Moderation();

    public Search getSearch() {
        return search;
    }

    public void setSearch(Search search) {
        this.search = search;
    }

    public Moderation getModeration() {
        return moderation;
    }

    public void setModeration(Moderation moderation) {
        this.moderation = moderation;
    }

    /**
     * Moderation-lifecycle windows. {@code activeDuration} is the advertised user-visible
     * lifetime and is only ever spent once a spot is published, never while it waits on
     * moderation. See {@code ModerationPolicy} for how the windows compose.
     */
    public static class Moderation {

        private Duration activeDuration = Duration.ofMinutes(10);
        private Duration validationTimeout = Duration.ofMinutes(2);
        private Duration validationRetryBackoff = Duration.ofMinutes(1);
        private int maxValidationAttempts = 3;
        /** Human review budget after AI lands the spot in PENDING_REVIEW. */
        private Duration reviewTimeout = Duration.ofMinutes(15);
        /**
         * Hard ceiling from submission time. Approvals past this age fail as
         * REVIEW_FAILED (stale) instead of publishing a fresh TTL for an old report.
         */
        private Duration maxPublishableAge = Duration.ofMinutes(30);

        public Duration getActiveDuration() {
            return activeDuration;
        }

        public void setActiveDuration(Duration activeDuration) {
            this.activeDuration = activeDuration;
        }

        public Duration getValidationTimeout() {
            return validationTimeout;
        }

        public void setValidationTimeout(Duration validationTimeout) {
            this.validationTimeout = validationTimeout;
        }

        public Duration getValidationRetryBackoff() {
            return validationRetryBackoff;
        }

        public void setValidationRetryBackoff(Duration validationRetryBackoff) {
            this.validationRetryBackoff = validationRetryBackoff;
        }

        public int getMaxValidationAttempts() {
            return maxValidationAttempts;
        }

        public void setMaxValidationAttempts(int maxValidationAttempts) {
            this.maxValidationAttempts = maxValidationAttempts;
        }

        public Duration getReviewTimeout() {
            return reviewTimeout;
        }

        public void setReviewTimeout(Duration reviewTimeout) {
            this.reviewTimeout = reviewTimeout;
        }

        public Duration getMaxPublishableAge() {
            return maxPublishableAge;
        }

        public void setMaxPublishableAge(Duration maxPublishableAge) {
            this.maxPublishableAge = maxPublishableAge;
        }
    }

    public static class Search {

        private double defaultRadiusMeters = 1000;
        private int defaultResultLimit = 10;
        private double maxRadiusMeters = 50000;
        private int maxResultLimit = 50;

        public double getDefaultRadiusMeters() {
            return defaultRadiusMeters;
        }

        public void setDefaultRadiusMeters(double defaultRadiusMeters) {
            this.defaultRadiusMeters = defaultRadiusMeters;
        }

        public int getDefaultResultLimit() {
            return defaultResultLimit;
        }

        public void setDefaultResultLimit(int defaultResultLimit) {
            this.defaultResultLimit = defaultResultLimit;
        }

        public double getMaxRadiusMeters() {
            return maxRadiusMeters;
        }

        public void setMaxRadiusMeters(double maxRadiusMeters) {
            this.maxRadiusMeters = maxRadiusMeters;
        }

        public int getMaxResultLimit() {
            return maxResultLimit;
        }

        public void setMaxResultLimit(int maxResultLimit) {
            this.maxResultLimit = maxResultLimit;
        }
    }
}
