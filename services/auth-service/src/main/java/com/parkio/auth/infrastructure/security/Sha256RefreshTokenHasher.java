package com.parkio.auth.infrastructure.security;

import com.parkio.auth.application.port.RefreshTokenHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * SHA-256 hash of opaque refresh tokens. The tokens are high-entropy random
 * values, so a fast deterministic digest is appropriate and lets us look a
 * token up by its hash. Only the hash is ever stored (ai-context/07).
 */
@Component
public class Sha256RefreshTokenHasher implements RefreshTokenHasher {

    @Override
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on every JVM.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
