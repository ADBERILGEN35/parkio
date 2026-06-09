package com.parkio.auth.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code parkio.security.jwt.*}. Normal environments must provide a PKCS#8
 * RSA private key PEM. Ephemeral generation is explicit and intended only for
 * dev/test profiles.
 */
@Validated
@ConfigurationProperties(prefix = "parkio.security.jwt")
public class JwtProperties {

    private String privateKeyPem;

    @NotBlank
    private String keyId = "parkio-auth-rs256-1";

    private boolean generateEphemeralKey;

    @NotBlank
    private String issuer;
    private Duration accessTokenTtl;
    private Duration refreshTokenTtl;

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public boolean isGenerateEphemeralKey() {
        return generateEphemeralKey;
    }

    public void setGenerateEphemeralKey(boolean generateEphemeralKey) {
        this.generateEphemeralKey = generateEphemeralKey;
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
