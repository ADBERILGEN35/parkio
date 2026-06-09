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
}
