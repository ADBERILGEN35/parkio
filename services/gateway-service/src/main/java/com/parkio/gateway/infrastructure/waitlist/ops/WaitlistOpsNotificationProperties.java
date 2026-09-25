package com.parkio.gateway.infrastructure.waitlist.ops;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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

    /**
     * Envelope contract version written by the exporter.
     * Keep at 1 until the slack_biz relay dual-read (v1+v2) is deployed;
     * then set to 2 to emit fullName + export-time counts.
     */
    @Min(1)
    @Max(2)
    private int contractVersion = 1;

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

    /** Stop exporting while the inbox holds this many unconsumed envelopes. */
    @Min(1)
    private int maxInboxBacklog = 5000;

    /** Stop exporting while the inbox filesystem has less usable space than this. */
    @Min(0)
    private long minFreeBytes = 512L * 1024 * 1024;

    /** EXPORTED / FAILED rows older than this are purged. */
    @NotNull
    private Duration retention = Duration.ofDays(30);

    /**
     * Control directory for the correlated export-pause handshake. The
     * coordinator writes {@code request}; the exporter writes {@code ack}
     * only after in-flight work finishes. Empty means no file gate.
     * Later gateway deploy must set
     * {@code PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_PAUSE_FILE}.
     */
    private String exportPauseFile = "";

    /** In-process pause used by tests and the isolated adapter. Does not disable admission. */
    private boolean exportPaused = false;

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

    public int getMaxInboxBacklog() {
        return maxInboxBacklog;
    }

    public void setMaxInboxBacklog(int maxInboxBacklog) {
        this.maxInboxBacklog = maxInboxBacklog;
    }

    public long getMinFreeBytes() {
        return minFreeBytes;
    }

    public void setMinFreeBytes(long minFreeBytes) {
        this.minFreeBytes = minFreeBytes;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public int getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(int contractVersion) {
        this.contractVersion = contractVersion;
    }

    public String getExportPauseFile() {
        return exportPauseFile;
    }

    public void setExportPauseFile(String exportPauseFile) {
        this.exportPauseFile = exportPauseFile;
    }

    public boolean isExportPaused() {
        return exportPaused;
    }

    public void setExportPaused(boolean exportPaused) {
        this.exportPaused = exportPaused;
    }

    /** True when the export loop must skip inbox writes without touching admission. */
    public boolean exportLoopIsPaused() {
        if (exportPaused) {
            return true;
        }
        if (pauseControlUnreadable()) {
            return true;
        }
        Path request = pauseRequestPath();
        return request != null && Files.isRegularFile(request);
    }

    public Path exportPauseControlDir() {
        if (exportPauseFile == null || exportPauseFile.isBlank()) {
            return null;
        }
        return Path.of(exportPauseFile);
    }

    /**
     * Readable {@code requestId} from the current pause request, or null when
     * no request is present. Unreadable control state is {@link #pauseControlUnreadable()}.
     */
    public String pauseRequestId() {
        Path request = pauseRequestPath();
        if (request == null) {
            return null;
        }
        try {
            String text = Files.readString(request);
            for (String line : text.split("\\R")) {
                if (line.startsWith("requestId=")) {
                    String value = line.substring("requestId=".length()).strip();
                    return value.isBlank() ? null : value;
                }
            }
            return null;
        } catch (NoSuchFileException ex) {
            return null;
        } catch (IOException ex) {
            return null;
        }
    }

    public boolean pauseControlUnreadable() {
        Path request = pauseRequestPath();
        if (request == null) {
            return false;
        }
        try {
            if (!Files.exists(request)) {
                return false;
            }
            Files.readString(request);
            return false;
        } catch (NoSuchFileException ex) {
            return false;
        } catch (IOException ex) {
            return true;
        }
    }

    Path pauseRequestPath() {
        Path dir = exportPauseControlDir();
        if (dir == null) {
            return null;
        }
        if (Files.isDirectory(dir)) {
            return dir.resolve("request");
        }
        return dir;
    }

    Path pauseAckPath() {
        Path dir = exportPauseControlDir();
        if (dir == null) {
            return null;
        }
        if (Files.isDirectory(dir)) {
            return dir.resolve("ack");
        }
        return Path.of(dir.toString() + ".ack");
    }
}
