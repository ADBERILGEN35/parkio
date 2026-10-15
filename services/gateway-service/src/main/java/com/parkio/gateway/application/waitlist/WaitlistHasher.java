package com.parkio.gateway.application.waitlist;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * CL-N08 / R-06: email identity HMAC and bearer-token HMAC may use different secrets so email-key
 * rotation does not invalidate outstanding confirm/unsubscribe links. When the token secret is
 * blank, both roles share {@code parkio.waitlist.hash-secret} (backward compatible). That shared
 * mode must not be combined with {@code hash-secret-previous}; see
 * {@link WaitlistKeyRetirementGuard}.
 */
@Component
public class WaitlistHasher {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private final SecretKeySpec emailCurrentKey;
    private final SecretKeySpec emailPreviousKey;
    private final SecretKeySpec tokenCurrentKey;
    private final SecretKeySpec tokenPreviousKey;

    public WaitlistHasher(WaitlistProperties properties) {
        this.emailCurrentKey = requireKey(properties.getHashSecret(), "parkio.waitlist.hash-secret");
        this.emailPreviousKey = optionalPrevious(
                properties.getHashSecretPrevious(), properties.getHashSecret(), "parkio.waitlist.hash-secret-previous");
        String tokenSecret = properties.getHashTokenSecret();
        if (tokenSecret == null || tokenSecret.isBlank()) {
            tokenSecret = properties.getHashSecret();
        }
        this.tokenCurrentKey = requireKey(tokenSecret, "parkio.waitlist.hash-token-secret");
        String tokenPrevious = properties.getHashTokenSecretPrevious();
        if (tokenPrevious == null || tokenPrevious.isBlank()) {
            // Fall back to email previous only when token secret itself falls back to email secret.
            if (properties.getHashTokenSecret() == null || properties.getHashTokenSecret().isBlank()) {
                this.tokenPreviousKey = this.emailPreviousKey;
            } else {
                this.tokenPreviousKey = null;
            }
        } else {
            this.tokenPreviousKey = optionalPrevious(
                    tokenPrevious, tokenSecret, "parkio.waitlist.hash-token-secret-previous");
        }
    }

    /** Hash with the current email secret only. Use for durable identity writes. */
    public String hash(String value) {
        return hmacHex(emailCurrentKey, value);
    }

    /** Hash with the current token secret only. Use for confirm/unsubscribe token digests. */
    public String hashToken(String value) {
        return hmacHex(tokenCurrentKey, value);
    }

    /**
     * Candidate digests for email identity lookups during email-secret rotation: current first,
     * then previous when configured.
     */
    public List<String> hashCandidates(String value) {
        return candidates(emailCurrentKey, emailPreviousKey, value);
    }

    /** Candidate digests for bearer-token lookups during token-secret rotation. */
    public List<String> hashTokenCandidates(String value) {
        return candidates(tokenCurrentKey, tokenPreviousKey, value);
    }

    /** True when the stored email digest already matches the current email secret. */
    public boolean isCurrentEmailHash(String email, String storedEmailHash) {
        if (email == null || storedEmailHash == null) {
            return true;
        }
        // Withdrawn placeholders are not HMAC identity digests.
        if (storedEmailHash.startsWith("withdrawn-")) {
            return true;
        }
        return hash(email).equals(storedEmailHash);
    }

    private static List<String> candidates(SecretKeySpec current, SecretKeySpec previous, String value) {
        Set<String> out = new LinkedHashSet<>();
        out.add(hmacHex(current, value));
        if (previous != null) {
            out.add(hmacHex(previous, value));
        }
        return new ArrayList<>(out);
    }

    private static SecretKeySpec requireKey(String secret, String name) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(name + " must be at least 32 characters.");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256);
    }

    private static SecretKeySpec optionalPrevious(String previous, String current, String name) {
        if (previous == null || previous.isBlank()) {
            return null;
        }
        if (previous.length() < 32) {
            throw new IllegalStateException(name + " must be at least 32 characters when set.");
        }
        if (previous.equals(current)) {
            return null;
        }
        return new SecretKeySpec(previous.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256);
    }

    private static String hmacHex(SecretKeySpec key, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(key);
            return toHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash waitlist metadata.", ex);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
