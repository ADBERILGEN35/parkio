package com.parkio.auth.infrastructure.durable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code parkio.privacy.account-erasure.durable-store.gcs.*}: a Google Cloud Storage bucket
 * with Object Retention Lock that holds durable erasure evidence format v2. Off by default.
 * Project, bucket, retention, trust document and producer key id are operator inputs with no
 * defaults; when enabled, a missing one stops the service at startup. Credentials use Application
 * Default Credentials (workload identity / service account) — not the erasure trust HMAC keys.
 */
@ConfigurationProperties(prefix = "parkio.privacy.account-erasure.durable-store.gcs")
public class GcsDurableStoreProperties {

    static final Set<String> RETENTION_MODES = Set.of("GOVERNANCE", "COMPLIANCE");

    private boolean enabled;
    private String projectId = "";
    private String bucket = "";
    private String retentionMode = "";
    private Duration retention;
    private String trustFile = "";
    private String producerKeyId = "";

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        requireText(problems, "project-id", projectId);
        requireText(problems, "bucket", bucket);
        requireText(problems, "trust-file", trustFile);
        requireText(problems, "producer-key-id", producerKeyId);
        if (!RETENTION_MODES.contains(retentionMode)) {
            problems.add("retention-mode (GOVERNANCE or COMPLIANCE)");
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            problems.add("retention (positive duration)");
        }
        return problems;
    }

    private static void requireText(List<String> problems, String name, String value) {
        if (value == null || value.isBlank()) {
            problems.add(name);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRetentionMode() {
        return retentionMode;
    }

    public void setRetentionMode(String retentionMode) {
        this.retentionMode = retentionMode == null ? "" : retentionMode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public String getTrustFile() {
        return trustFile;
    }

    public void setTrustFile(String trustFile) {
        this.trustFile = trustFile;
    }

    public String getProducerKeyId() {
        return producerKeyId;
    }

    public void setProducerKeyId(String producerKeyId) {
        this.producerKeyId = producerKeyId;
    }
}
