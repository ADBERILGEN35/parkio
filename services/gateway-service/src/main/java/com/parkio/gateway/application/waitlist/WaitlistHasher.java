package com.parkio.gateway.application.waitlist;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class WaitlistHasher {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private final SecretKeySpec currentKey;
    private final SecretKeySpec previousKey;

    public WaitlistHasher(WaitlistProperties properties) {
        String secret = properties.getHashSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("parkio.waitlist.hash-secret must be at least 32 characters.");
        }
        this.currentKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256);
        String previous = properties.getHashSecretPrevious();
        if (previous == null || previous.isBlank()) {
            this.previousKey = null;
        } else {
            if (previous.length() < 32) {
                throw new IllegalStateException(
                        "parkio.waitlist.hash-secret-previous must be at least 32 characters when set.");
            }
            if (previous.equals(secret)) {
                this.previousKey = null;
            } else {
                this.previousKey = new SecretKeySpec(previous.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256);
            }
        }
    }

    /** Hash with the current secret only. Use for all durable writes. */
    public String hash(String value) {
        return hmacHex(currentKey, value);
    }

    /**
     * Candidate digests for lookups during a secret rotation: current first, then
     * previous when configured. Writers must keep using {@link #hash(String)}.
     */
    public List<String> hashCandidates(String value) {
        Set<String> out = new LinkedHashSet<>();
        out.add(hmacHex(currentKey, value));
        if (previousKey != null) {
            out.add(hmacHex(previousKey, value));
        }
        return new ArrayList<>(out);
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
