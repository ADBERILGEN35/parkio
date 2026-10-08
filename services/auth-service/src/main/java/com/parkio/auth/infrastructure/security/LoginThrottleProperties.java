package com.parkio.auth.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The login throttle's keyed-hashing secret (CL-F15 v3). {@code hmac-key} is managed separately from
 * every other secret (it is not derived from and has no fallback to another one); {@code
 * hmac-key-previous} is read-only during a rotation's overlap; {@code generate-ephemeral-hmac-key}
 * lets a local-development profile run without a configured key (throttle state then does not survive
 * a restart) and must stay off everywhere else.
 */
@ConfigurationProperties(prefix = "parkio.security.login-throttle")
public class LoginThrottleProperties {

    private String hmacKey;
    private String hmacKeyPrevious;
    private boolean generateEphemeralHmacKey;

    public String getHmacKey() {
        return hmacKey;
    }

    public void setHmacKey(String hmacKey) {
        this.hmacKey = hmacKey;
    }

    public String getHmacKeyPrevious() {
        return hmacKeyPrevious;
    }

    public void setHmacKeyPrevious(String hmacKeyPrevious) {
        this.hmacKeyPrevious = hmacKeyPrevious;
    }

    public boolean isGenerateEphemeralHmacKey() {
        return generateEphemeralHmacKey;
    }

    public void setGenerateEphemeralHmacKey(boolean generateEphemeralHmacKey) {
        this.generateEphemeralHmacKey = generateEphemeralHmacKey;
    }
}
