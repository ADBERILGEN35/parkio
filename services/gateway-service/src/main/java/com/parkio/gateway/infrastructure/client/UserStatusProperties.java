package com.parkio.gateway.infrastructure.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code parkio.gateway.user-status.*}: how the gateway reaches user-service for
 * the per-request account-status check, how long a resolved status is cached, and how
 * long to wait before treating the lookup as unavailable.
 */
@ConfigurationProperties(prefix = "parkio.gateway.user-status")
public class UserStatusProperties {

    /** Base URL of user-service (internal network); the status path is appended. */
    private String baseUrl = "http://localhost:8082";

    /** How long a resolved (found) status is cached before re-checking. Small by design. */
    private Duration cacheTtl = Duration.ofSeconds(30);

    /** Max wait for the status lookup before failing closed as unavailable. */
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
