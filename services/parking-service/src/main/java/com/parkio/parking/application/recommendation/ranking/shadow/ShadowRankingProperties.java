package com.parkio.parking.application.recommendation.ranking.shadow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shadow ranking knobs ({@code parkio.spa.ranking.shadow.*}). Defaults keep the
 * framework off: enabled=false, sampleRate=0.0.
 */
@ConfigurationProperties(prefix = "parkio.spa.ranking.shadow")
public class ShadowRankingProperties {

    private boolean enabled = false;
    private double sampleRate = 0.0;
    private long timeoutMs = 500L;
    private int maxCandidates = 10;
    private int maxConcurrent = 4;

    public ShadowConfiguration snapshot() {
        return new ShadowConfiguration(
                enabled, clampedSampleRate(), Math.max(1L, timeoutMs), Math.max(1, maxCandidates), Math.max(1, maxConcurrent));
    }

    double clampedSampleRate() {
        if (!Double.isFinite(sampleRate) || sampleRate < 0.0) {
            return 0.0;
        }
        if (sampleRate > 1.0) {
            return 1.0;
        }
        return sampleRate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        this.sampleRate = sampleRate;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public record ShadowConfiguration(
            boolean enabled, double sampleRate, long timeoutMs, int maxCandidates, int maxConcurrent) {}
}
