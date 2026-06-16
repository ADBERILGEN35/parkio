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

    /**
     * Optional JSON array of <strong>previous</strong> public verification keys to keep in
     * the JWKS during a key rotation, e.g.
     * {@code [{"kid":"parkio-auth-rs256-1","pem":"-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"}]}.
     * Signing always uses the single active key ({@link #keyId}); these are exposed for
     * verification only so access tokens issued by the old key stay valid until they expire.
     * Empty by default (no rotation in progress). Env: {@code PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON}.
     */
    private String additionalPublicKeysJson;

    private boolean generateEphemeralKey;

    @NotBlank
    private String issuer;

    /**
     * {@code aud} claim stamped on every issued access token. The gateway rejects
     * tokens whose audience does not match its own configured value
     * ({@code PARKIO_JWT_AUDIENCE}); fail-closed at startup when blank.
     */
    @NotBlank
    private String audience;

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

    public String getAdditionalPublicKeysJson() {
        return additionalPublicKeysJson;
    }

    public void setAdditionalPublicKeysJson(String additionalPublicKeysJson) {
        this.additionalPublicKeysJson = additionalPublicKeysJson;
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

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
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
