package com.parkio.auth.infrastructure.durable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code parkio.privacy.account-erasure.durable-store.object-lock.*}: an S3-compatible
 * bucket with object lock (versioning) that holds durable erasure evidence format v2. Off by
 * default. Bucket, credentials, retention, the trust document (pinned database identity and
 * producer keys) and the signing key id are operator inputs with no defaults; when enabled, a
 * missing one stops the service at startup.
 */
@ConfigurationProperties(prefix = "parkio.privacy.account-erasure.durable-store.object-lock")
public class ObjectLockStoreProperties {

    static final Set<String> RETENTION_MODES = Set.of("GOVERNANCE", "COMPLIANCE");

    private boolean enabled;
    private String endpoint = "";
    private String region = "us-east-1";
    private String bucket = "";
    private String accessKey = "";
    private String secretKey = "";
    private String retentionMode = "";
    private Duration retention;
    private String trustFile = "";
    private String producerKeyId = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration callTimeout = Duration.ofSeconds(15);

    /** Names of missing or invalid settings; values are never included. */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        requireText(problems, "endpoint", endpoint);
        requireText(problems, "bucket", bucket);
        requireText(problems, "access-key", accessKey);
        requireText(problems, "secret-key", secretKey);
        requireText(problems, "trust-file", trustFile);
        requireText(problems, "producer-key-id", producerKeyId);
        if (!RETENTION_MODES.contains(retentionMode)) {
            problems.add("retention-mode (GOVERNANCE or COMPLIANCE)");
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            problems.add("retention (positive duration)");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            problems.add("connect-timeout");
        }
        if (callTimeout == null || callTimeout.isZero() || callTimeout.isNegative()) {
            problems.add("call-timeout");
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

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
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

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getCallTimeout() {
        return callTimeout;
    }

    public void setCallTimeout(Duration callTimeout) {
        this.callTimeout = callTimeout;
    }
}
