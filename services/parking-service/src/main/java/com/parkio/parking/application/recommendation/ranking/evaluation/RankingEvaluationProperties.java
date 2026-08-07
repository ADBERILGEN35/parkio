package com.parkio.parking.application.recommendation.ranking.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Privacy-safe ranking evaluation correlation + long-horizon rollup knobs
 * ({@code parkio.spa.ranking.evaluation.*}).
 */
@ConfigurationProperties(prefix = "parkio.spa.ranking.evaluation")
public class RankingEvaluationProperties {

    private boolean enabled = false;
    /** Maximum retention for evaluation snapshots and outcomes (hours). Cap 168 (7d). */
    private int retentionHours = 24;
    private boolean cleanupEnabled = true;
    private long cleanupFixedDelayMs = 900_000L;
    private int cleanupBatchSize = 500;

    /** WP-SPA-14D long-horizon privacy-safe rollups. Default off. */
    private boolean rollupEnabled = false;
    private int rollupRetentionDays = 90;
    private int rollupGraceMinutes = 60;
    private long rollupFixedDelayMs = 900_000L;
    private int rollupMaxSlicesPerPass = 24;
    private boolean rollupCleanupEnabled = true;
    private int reportMinCellCount = RankingEvaluationRollupConstants.MIN_REPORT_CELL_COUNT;

    public EvaluationConfiguration snapshot() {
        return new EvaluationConfiguration(
                enabled,
                clampedRetentionHours(),
                cleanupEnabled,
                Math.max(1, cleanupBatchSize),
                rollupEnabled,
                clampedRollupRetentionDays(),
                clampedGraceMinutes(),
                Math.max(1, rollupMaxSlicesPerPass),
                rollupCleanupEnabled,
                Math.max(1, reportMinCellCount));
    }

    int clampedRetentionHours() {
        if (retentionHours < 1) {
            return 1;
        }
        return Math.min(retentionHours, 168);
    }

    int clampedRollupRetentionDays() {
        if (rollupRetentionDays < 7) {
            return 7;
        }
        return Math.min(rollupRetentionDays, 365);
    }

    int clampedGraceMinutes() {
        if (rollupGraceMinutes < 15) {
            return 15;
        }
        return Math.min(rollupGraceMinutes, 360);
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

    public boolean isRollupEnabled() {
        return rollupEnabled;
    }

    public void setRollupEnabled(boolean rollupEnabled) {
        this.rollupEnabled = rollupEnabled;
    }

    public int getRollupRetentionDays() {
        return rollupRetentionDays;
    }

    public void setRollupRetentionDays(int rollupRetentionDays) {
        this.rollupRetentionDays = rollupRetentionDays;
    }

    public int getRollupGraceMinutes() {
        return rollupGraceMinutes;
    }

    public void setRollupGraceMinutes(int rollupGraceMinutes) {
        this.rollupGraceMinutes = rollupGraceMinutes;
    }

    public long getRollupFixedDelayMs() {
        return rollupFixedDelayMs;
    }

    public void setRollupFixedDelayMs(long rollupFixedDelayMs) {
        this.rollupFixedDelayMs = rollupFixedDelayMs;
    }

    public int getRollupMaxSlicesPerPass() {
        return rollupMaxSlicesPerPass;
    }

    public void setRollupMaxSlicesPerPass(int rollupMaxSlicesPerPass) {
        this.rollupMaxSlicesPerPass = rollupMaxSlicesPerPass;
    }

    public boolean isRollupCleanupEnabled() {
        return rollupCleanupEnabled;
    }

    public void setRollupCleanupEnabled(boolean rollupCleanupEnabled) {
        this.rollupCleanupEnabled = rollupCleanupEnabled;
    }

    public int getReportMinCellCount() {
        return reportMinCellCount;
    }

    public void setReportMinCellCount(int reportMinCellCount) {
        this.reportMinCellCount = reportMinCellCount;
    }

    public record EvaluationConfiguration(
            boolean enabled,
            int retentionHours,
            boolean cleanupEnabled,
            int cleanupBatchSize,
            boolean rollupEnabled,
            int rollupRetentionDays,
            int rollupGraceMinutes,
            int rollupMaxSlicesPerPass,
            boolean rollupCleanupEnabled,
            int reportMinCellCount) {}
}
