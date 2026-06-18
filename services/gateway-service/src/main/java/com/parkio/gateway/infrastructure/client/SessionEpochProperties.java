package com.parkio.gateway.infrastructure.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code parkio.gateway.session-epoch.*}: how the gateway reaches auth-service for
 * the per-request access-token revocation (session-epoch) check, how long a resolved
 * epoch is cached, and how long to wait before treating the lookup as unavailable.
 */
@ConfigurationProperties(prefix = "parkio.gateway.session-epoch")
public class SessionEpochProperties {

    /** Base URL of auth-service (internal network); the session-epoch path is appended. */
    private String baseUrl = "http://localhost:8081";

    /** How long a resolved epoch is cached before re-checking. Small by design. */
    private Duration cacheTtl = Duration.ofSeconds(30);

    /** Max wait for the epoch lookup before failing closed as unavailable. */
    private Duration requestTimeout = Duration.ofSeconds(2);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
