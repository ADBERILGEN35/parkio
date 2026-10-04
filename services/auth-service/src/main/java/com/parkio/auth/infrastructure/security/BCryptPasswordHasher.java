package com.parkio.auth.infrastructure.security;

import com.parkio.auth.application.port.PasswordHasher;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** BCrypt-backed {@link PasswordHasher} (ai-context/07). */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    /**
     * Same encoder and cost as stored hashes; random input, so no password matches it. A future
     * cost upgrade or rehash-on-login must build this hash with the encoder new passwords use.
     */
    private final String unknownAccountHash = encoder.encode(UUID.randomUUID().toString());

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }

    @Override
    public void compareWithoutAccount(String rawPassword) {
        encoder.matches(rawPassword, unknownAccountHash);
    }
}
