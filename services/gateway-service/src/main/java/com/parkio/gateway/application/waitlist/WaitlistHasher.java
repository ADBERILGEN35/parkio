package com.parkio.gateway.application.waitlist;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class WaitlistHasher {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private final SecretKeySpec key;

    public WaitlistHasher(WaitlistProperties properties) {
        String secret = properties.getHashSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("parkio.waitlist.hash-secret must be at least 32 characters.");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256);
    }

    public String hash(String value) {
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
