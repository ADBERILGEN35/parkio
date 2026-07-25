package com.parkio.parking.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code parkio.parking.*}: tunable nearby-search and moderation-lifecycle settings. */
@ConfigurationProperties(prefix = "parkio.parking")
public class ParkingProperties {

    private Search search = new Search();
    private Moderation moderation = new Moderation();
    private Session session = new Session();

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

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    /**
     * Parking-session stale lifecycle: confirmation, reminders, auto-complete, retention.
     * Durations use ISO-8601 ({@code PT24H}) and may be overridden by env vars.
     */
    public static class Session {

        private Duration confirmAfter = Duration.ofHours(24);
        private Duration reminder2After = Duration.ofHours(48);
        private Duration autoCompleteAfter = Duration.ofHours(72);
        private Duration schedulerRate = Duration.ofHours(1);
        private int schedulerBatchSize = 100;
        private boolean remindersEnabled = true;
        private boolean autoCompleteEnabled = true;
        private boolean notificationEnabled = true;
        private boolean retentionEnabled = false;
        private Duration retentionAfter = Duration.ofDays(365);

        public Duration getConfirmAfter() {
            return confirmAfter;
        }

        public void setConfirmAfter(Duration confirmAfter) {
            this.confirmAfter = confirmAfter;
        }

        public Duration getReminder2After() {
            return reminder2After;
        }

        public void setReminder2After(Duration reminder2After) {
            this.reminder2After = reminder2After;
        }

        public Duration getAutoCompleteAfter() {
            return autoCompleteAfter;
        }

        public void setAutoCompleteAfter(Duration autoCompleteAfter) {
            this.autoCompleteAfter = autoCompleteAfter;
        }

        public Duration getSchedulerRate() {
            return schedulerRate;
        }

        public void setSchedulerRate(Duration schedulerRate) {
            this.schedulerRate = schedulerRate;
        }

        public int getSchedulerBatchSize() {
            return schedulerBatchSize;
        }

        public void setSchedulerBatchSize(int schedulerBatchSize) {
            this.schedulerBatchSize = schedulerBatchSize;
        }

        public boolean isRemindersEnabled() {
            return remindersEnabled;
        }

        public void setRemindersEnabled(boolean remindersEnabled) {
            this.remindersEnabled = remindersEnabled;
        }

        public boolean isAutoCompleteEnabled() {
            return autoCompleteEnabled;
        }

        public void setAutoCompleteEnabled(boolean autoCompleteEnabled) {
            this.autoCompleteEnabled = autoCompleteEnabled;
        }

        public boolean isNotificationEnabled() {
            return notificationEnabled;
        }

        public void setNotificationEnabled(boolean notificationEnabled) {
            this.notificationEnabled = notificationEnabled;
        }

        public boolean isRetentionEnabled() {
            return retentionEnabled;
        }

        public void setRetentionEnabled(boolean retentionEnabled) {
            this.retentionEnabled = retentionEnabled;
        }

        public Duration getRetentionAfter() {
            return retentionAfter;
        }

        public void setRetentionAfter(Duration retentionAfter) {
            this.retentionAfter = retentionAfter;
        }
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
