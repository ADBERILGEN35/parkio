package com.parkio.auth.application.port;

/** Port for password hashing/verification (BCrypt in infrastructure). */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
