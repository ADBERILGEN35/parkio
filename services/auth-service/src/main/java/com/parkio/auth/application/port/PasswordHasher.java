package com.parkio.auth.application.port;

/** Port for password hashing/verification (BCrypt in infrastructure). */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);

    /**
     * Spends what {@link #matches} spends on a stored hash, against a hash no password matches.
     * Login calls it for an unknown e-mail, so rejecting an unknown account takes as long as
     * rejecting a wrong password (CL-F14.2). The outcome is never used.
     */
    default void compareWithoutAccount(String rawPassword) {
    }
}
