package com.parkio.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed digests for the login throttle's Redis key space (CL-F15 v3). E-mails and client keys are
 * stored as HMAC-SHA256 digests under a secret that is managed separately from every other secret, so a
 * copy of Redis cannot be reversed offline into account-to-address associations (an unsalted digest of
 * an IPv4 address or a dictionary e-mail can). Each secret has a key id derived from it; the id prefixes
 * every key written under that secret, so a rotation leaves the old entries readable under
 * {@code previous} for the overlap and never mixes digests of two secrets.
 *
 * <p>Rotation: set the new secret as {@code hmac-key} and the old one as {@code hmac-key-previous};
 * writes go to the new key id, lookups consult both; after the overlap ({@link
 * LoginThrottlePolicy#HMAC_KEY_OVERLAP}, long enough for every entry written under the old secret to
 * expire) remove the previous secret. A compromised secret is rotated the same way without an overlap.
 */
public final class LoginThrottleKeys {

    public static final int MIN_LENGTH = 32;
    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final String KEY_ID_INPUT = "parkio-login-throttle-key-id";

    private final SecretKeySpec current;
    private final SecretKeySpec previous;
    private final String kid;
    private final String previousKid;

    private LoginThrottleKeys(SecretKeySpec current, SecretKeySpec previous) {
        this.current = current;
        this.previous = previous;
        this.kid = hmacHex(current, KEY_ID_INPUT, 4);
        this.previousKid = previous == null ? null : hmacHex(previous, KEY_ID_INPUT, 4);
    }

    /** The configured secret (at least {@value #MIN_LENGTH} characters) and, during a rotation, the previous one. */
    public static LoginThrottleKeys of(String currentSecret, String previousSecret) {
        if (currentSecret == null || currentSecret.isBlank() || currentSecret.length() < MIN_LENGTH) {
            throw new IllegalStateException("the login throttle HMAC key must be at least " + MIN_LENGTH + " characters");
        }
        SecretKeySpec previous = null;
        if (previousSecret != null && !previousSecret.isBlank()) {
            if (previousSecret.length() < MIN_LENGTH) {
                throw new IllegalStateException(
                        "the previous login throttle HMAC key must be at least " + MIN_LENGTH + " characters");
            }
            if (previousSecret.equals(currentSecret)) {
                throw new IllegalStateException("the previous login throttle HMAC key must differ from the current one");
            }
            previous = spec(previousSecret);
        }
        return new LoginThrottleKeys(spec(currentSecret), previous);
    }

    /** A random secret for this process only (local development): throttle state does not survive a restart. */
    public static LoginThrottleKeys ephemeral() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new LoginThrottleKeys(spec(Base64.getEncoder().encodeToString(bytes)), null);
    }

    /** Eight hex characters identifying the current secret; prefixes every key written under it. */
    public String kid() {
        return kid;
    }

    public boolean hasPrevious() {
        return previous != null;
    }

    /** The previous secret's key id, or null outside a rotation overlap. */
    public String previousKid() {
        return previousKid;
    }

    /** 32 hex characters: HMAC-SHA256 of the value under the current secret, truncated to 16 bytes. */
    public String digest(String value) {
        return hmacHex(current, value == null ? "" : value, 16);
    }

    /** The same digest under the previous secret; null outside a rotation overlap. */
    public String previousDigest(String value) {
        return previous == null ? null : hmacHex(previous, value == null ? "" : value, 16);
    }

    private static SecretKeySpec spec(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256);
    }

    private static String hmacHex(SecretKeySpec key, String value, int bytes) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)), 0, bytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
