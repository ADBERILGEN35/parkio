package com.parkio.auth.infrastructure.security;

import com.parkio.auth.application.port.SecureTokenGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Generates opaque refresh token values from 256 bits of secure randomness,
 * URL-safe Base64 encoded. The raw value is returned to the client once and
 * only its hash is persisted.
 */
@Component
public class SecureRandomTokenGenerator implements SecureTokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    @Override
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
