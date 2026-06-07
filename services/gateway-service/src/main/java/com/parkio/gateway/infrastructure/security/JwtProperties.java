package com.parkio.gateway.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code parkio.security.jwt.*}. The secret and issuer mirror auth-service —
 * the gateway verifies the same HS256 tokens it issues. The secret has no default;
 * validation fails startup if it is missing or too short for HS256 (fail closed,
 * ai-context/07).
 */
@Validated
@ConfigurationProperties(prefix = "parkio.security.jwt")
public class JwtProperties {

    @NotBlank
    @Size(min = 32, message = "JWT secret must be at least 32 characters for HS256")
    private String secret;

    @NotBlank
    private String issuer;

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
}
