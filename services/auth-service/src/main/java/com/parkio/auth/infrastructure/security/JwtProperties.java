package com.parkio.auth.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code parkio.security.jwt.*}. The secret has no default — it must be
 * supplied via configuration (the {@code dev} profile carries a non-production
 * value; other environments must set {@code PARKIO_JWT_SECRET}). Validation
 * fails startup if it is missing or too short for HS256 (fail closed,
 * ai-context/07).
 */
@Validated
@ConfigurationProperties(prefix = "parkio.security.jwt")
public class JwtProperties {

    @NotBlank
    @Size(min = 32, message = "JWT secret must be at least 32 characters for HS256")
    private String secret;
    private String issuer;
    private Duration accessTokenTtl;
    private Duration refreshTokenTtl;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }
}
