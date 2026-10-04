package com.parkio.gateway.infrastructure.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code parkio.security.jwt.*}. The gateway trusts only RS256 public keys
 * loaded from auth-service's configured JWKS endpoint.
 */
@Validated
@ConfigurationProperties(prefix = "parkio.security.jwt")
public class JwtProperties {

    @NotBlank
    private String issuer;

    /**
     * Expected {@code aud} claim. Tokens whose audience is missing or does not
     * contain this value are rejected. Must match auth-service's
     * {@code PARKIO_JWT_AUDIENCE}; fail-closed at startup when blank.
     */
    @NotBlank
    private String audience;

    /**
     * Tolerance applied to time-based claim checks ({@code exp}/{@code nbf}) to
     * absorb small clock drift between auth-service and the gateway.
     */
    @Min(0)
    private long clockSkewSeconds = 30;

    @NotBlank
    private String jwksUri;

    private Duration jwksCacheTtl = Duration.ofMinutes(15);

    /*
     * Bounds for refreshes forced by an unknown kid (U10). The values are policy choices:
     * at most one forced refresh per cooldown and at most `budget` per window, unknown
     * kids remembered for the negative-cache TTL in a cache of bounded size, and every
     * JWKS fetch bounded by the fetch timeout. A new legitimate key is accepted at the
     * first request after the cooldown while the budget lasts, and in any case at the
     * next regular refresh (jwks-cache-ttl).
     */
    private Duration jwksRefreshCooldown = Duration.ofSeconds(30);

    @Min(1)
    private int jwksRefreshBudget = 20;

    private Duration jwksRefreshBudgetWindow = Duration.ofHours(1);

    @Min(1)
    private int jwksNegativeCacheSize = 1000;

    private Duration jwksNegativeCacheTtl = Duration.ofMinutes(5);

    private Duration jwksFetchTimeout = Duration.ofSeconds(10);

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public Duration getJwksCacheTtl() {
        return jwksCacheTtl;
    }

    public void setJwksCacheTtl(Duration jwksCacheTtl) {
        this.jwksCacheTtl = jwksCacheTtl;
    }

    public Duration getJwksRefreshCooldown() {
        return jwksRefreshCooldown;
    }

    public void setJwksRefreshCooldown(Duration jwksRefreshCooldown) {
        this.jwksRefreshCooldown = jwksRefreshCooldown;
    }

    public int getJwksRefreshBudget() {
        return jwksRefreshBudget;
    }

    public void setJwksRefreshBudget(int jwksRefreshBudget) {
        this.jwksRefreshBudget = jwksRefreshBudget;
    }

    public Duration getJwksRefreshBudgetWindow() {
        return jwksRefreshBudgetWindow;
    }

    public void setJwksRefreshBudgetWindow(Duration jwksRefreshBudgetWindow) {
        this.jwksRefreshBudgetWindow = jwksRefreshBudgetWindow;
    }

    public int getJwksNegativeCacheSize() {
        return jwksNegativeCacheSize;
    }

    public void setJwksNegativeCacheSize(int jwksNegativeCacheSize) {
        this.jwksNegativeCacheSize = jwksNegativeCacheSize;
    }

    public Duration getJwksNegativeCacheTtl() {
        return jwksNegativeCacheTtl;
    }

    public void setJwksNegativeCacheTtl(Duration jwksNegativeCacheTtl) {
        this.jwksNegativeCacheTtl = jwksNegativeCacheTtl;
    }

    public Duration getJwksFetchTimeout() {
        return jwksFetchTimeout;
    }

    public void setJwksFetchTimeout(Duration jwksFetchTimeout) {
        this.jwksFetchTimeout = jwksFetchTimeout;
    }
}
