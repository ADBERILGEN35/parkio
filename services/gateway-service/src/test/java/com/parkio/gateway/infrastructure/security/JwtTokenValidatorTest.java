package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtTokenValidatorTest {

    private static final String SECRET = "unit-test-parkio-jwt-secret-0123456789-abcdefghij";
    private static final String ISSUER = "parkio-auth";

    private final JwtTokenValidator validator = new JwtTokenValidator(properties(SECRET, ISSUER));

    @Test
    void validatesWellFormedTokenAndExtractsClaims() {
        UUID userId = UUID.randomUUID();
        String token = tokenBuilder(SECRET, ISSUER)
                .subject(userId.toString())
                .claim("email", "rider@parkio.test")
                .claim("roles", List.of("USER", "ADMIN"))
                .claim("status", "ACTIVE")
                .compact();

        AuthenticatedUser user = validator.validate(token);

        assertThat(user.userId()).isEqualTo(userId.toString());
        assertThat(user.email()).isEqualTo("rider@parkio.test");
        assertThat(user.roles()).containsExactly("USER", "ADMIN");
        assertThat(user.status()).isEqualTo("ACTIVE");
    }

    @Test
    void defaultsRolesToEmptyWhenClaimAbsent() {
        String token = tokenBuilder(SECRET, ISSUER)
                .subject(UUID.randomUUID().toString())
                .compact();

        AuthenticatedUser user = validator.validate(token);

        assertThat(user.roles()).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        Instant now = Instant.now();
        String token = tokenBuilder(SECRET, ISSUER)
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minus(2, ChronoUnit.HOURS)))
                .expiration(Date.from(now.minus(1, ChronoUnit.HOURS)))
                .compact();

        assertThatThrownBy(() -> validator.validate(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        String token = tokenBuilder("a-completely-different-secret-0123456789-abcdef", ISSUER)
                .subject(UUID.randomUUID().toString())
                .compact();

        assertThatThrownBy(() -> validator.validate(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenWithWrongIssuer() {
        String token = tokenBuilder(SECRET, "evil-issuer")
                .subject(UUID.randomUUID().toString())
                .compact();

        assertThatThrownBy(() -> validator.validate(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsGarbageToken() {
        assertThatThrownBy(() -> validator.validate("not-a-jwt"))
                .isInstanceOfAny(JwtException.class, IllegalArgumentException.class);
    }

    private static JwtProperties properties(String secret, String issuer) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setIssuer(issuer);
        return properties;
    }

    private static io.jsonwebtoken.JwtBuilder tokenBuilder(String secret, String issuer) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith(key);
    }
}
