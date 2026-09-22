package com.parkio.gateway.infrastructure.waitlist.ops;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Waitlist operational notifications. Disabled by default: when disabled no
 * outbox rows are written and the exporter bean does not exist.
 *
 * <p>The gateway never talks to Slack. It exports sanitized envelopes into
 * {@code exportDir}, which is the file inbox of the existing slack_biz relay
 * ({@code scripts/slack_biz}); the relay owns the webhook secret, retries,
 * 429 handling and dedup.
 */
@Validated
@ConfigurationProperties(prefix = "parkio.waitlist.ops-notifications")
public class WaitlistOpsNotificationProperties {

    private boolean enabled = false;

    @NotBlank
    private String environment = "local";

    /** slack_biz waitlist inbox directory; required when enabled. */
    private String exportDir;

    @NotNull
    private Duration pollInterval = Duration.ofSeconds(30);

    /** Upper bound of envelopes exported per poll (storm guard). */
    @Min(1)
    @Max(500)
    private int batchSize = 20;

    @Min(1)
    @Max(20)
    private int maxExportAttempts = 5;

    @NotNull
    private Duration retryBaseDelay = Duration.ofSeconds(30);

    @NotNull
    private Duration retryMaxDelay = Duration.ofMinutes(15);

    /** EXPORTED / FAILED rows older than this are purged. */
    @NotNull
    private Duration retention = Duration.ofDays(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getExportDir() {
        return exportDir;
    }

    public void setExportDir(String exportDir) {
        this.exportDir = exportDir;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxExportAttempts() {
        return maxExportAttempts;
    }

    public void setMaxExportAttempts(int maxExportAttempts) {
        this.maxExportAttempts = maxExportAttempts;
    }

    public Duration getRetryBaseDelay() {
        return retryBaseDelay;
    }

    public void setRetryBaseDelay(Duration retryBaseDelay) {
        this.retryBaseDelay = retryBaseDelay;
    }

    public Duration getRetryMaxDelay() {
        return retryMaxDelay;
    }

    public void setRetryMaxDelay(Duration retryMaxDelay) {
        this.retryMaxDelay = retryMaxDelay;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }
}
