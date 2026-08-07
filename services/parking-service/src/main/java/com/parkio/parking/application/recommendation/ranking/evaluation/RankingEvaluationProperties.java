package com.parkio.parking.application.recommendation.ranking.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Privacy-safe ranking evaluation correlation knobs ({@code parkio.spa.ranking.evaluation.*}).
 * Default off — no durable evaluation records unless explicitly enabled.
 */
@ConfigurationProperties(prefix = "parkio.spa.ranking.evaluation")
public class RankingEvaluationProperties {

    private boolean enabled = false;
    /** Maximum retention for evaluation snapshots and outcomes (hours). Cap 168 (7d). */
    private int retentionHours = 24;
    private boolean cleanupEnabled = true;
    private long cleanupFixedDelayMs = 900_000L;
    private int cleanupBatchSize = 500;

    public EvaluationConfiguration snapshot() {
        return new EvaluationConfiguration(
                enabled, clampedRetentionHours(), cleanupEnabled, Math.max(1, cleanupBatchSize));
    }

    int clampedRetentionHours() {
        if (retentionHours < 1) {
            return 1;
        }
        return Math.min(retentionHours, 168);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRetentionHours() {
        return retentionHours;
    }

    public void setRetentionHours(int retentionHours) {
        this.retentionHours = retentionHours;
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }

    public long getCleanupFixedDelayMs() {
        return cleanupFixedDelayMs;
    }

    public void setCleanupFixedDelayMs(long cleanupFixedDelayMs) {
        this.cleanupFixedDelayMs = cleanupFixedDelayMs;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }

    public record EvaluationConfiguration(
            boolean enabled, int retentionHours, boolean cleanupEnabled, int cleanupBatchSize) {}
}
