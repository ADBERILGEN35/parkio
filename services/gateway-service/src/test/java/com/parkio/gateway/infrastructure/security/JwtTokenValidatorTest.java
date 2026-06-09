package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class JwtTokenValidatorTest {

    private static final String KEY_ID = "test-key";
    private static final String ISSUER = "parkio-auth";
    private static final String AUDIENCE = "parkio-api";
    private static final long CLOCK_SKEW_SECONDS = 30;
    private static KeyPair signingKeys;
    private static KeyPair otherKeys;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKeys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
    }

    @Test
    void validatesWellFormedTokenAndExtractsClaims() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        UUID userId = UUID.randomUUID();
        String token = tokenBuilder(KEY_ID, ISSUER, signingKeys)
                .subject(userId.toString())
                .claim("email", "rider@parkio.test")
                .claim("roles", List.of("USER", "ADMIN"))
                .claim("status", "ACTIVE")
                .compact();

        AuthenticatedUser user = validator.validate(token).block();

        assertThat(user.userId()).isEqualTo(userId.toString());
        assertThat(user.email()).isEqualTo("rider@parkio.test");
        assertThat(user.roles()).containsExactly("USER", "ADMIN");
        assertThat(user.status()).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsTokenWithMissingAudience() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        String token = tokenBuilderWithoutAudience(KEY_ID, ISSUER, signingKeys)
                .subject(UUID.randomUUID().toString())
                .compact();

        assertThatThrownBy(() -> validator.validate(token).block())
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenWithWrongAudience() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        String token = tokenBuilderWithoutAudience(KEY_ID, ISSUER, signingKeys)
                .audience().add("some-other-api").and()
                .subject(UUID.randomUUID().toString())
                .compact();

        assertThatThrownBy(() -> validator.validate(token).block())
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        Instant now = Instant.now();
        String token = tokenBuilder(KEY_ID, ISSUER, signingKeys)
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minus(2, ChronoUnit.HOURS)))
                .expiration(Date.from(now.minus(1, ChronoUnit.HOURS)))
                .compact();

        assertThatThrownBy(() -> validator.validate(token).block())
                .isInstanceOf(JwtException.class);
    }

    @Test
    void acceptsTokenExpiredWithinClockSkew() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        Instant now = Instant.now();
        String token = tokenBuilder(KEY_ID, ISSUER, signingKeys)
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minus(15, ChronoUnit.MINUTES)))
                // Expired 10s ago — inside the 30s skew window.
                .expiration(Date.from(now.minus(10, ChronoUnit.SECONDS)))
                .compact();

        AuthenticatedUser user = validator.validate(token).block();

        assertThat(user).isNotNull();
    }

    @Test
    void rejectsTokenExpiredBeyondClockSkew() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        Instant now = Instant.now();
        String token = tokenBuilder(KEY_ID, ISSUER, signingKeys)
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minus(15, ChronoUnit.MINUTES)))
                // Expired 90s ago — beyond the 30s skew window.
                .expiration(Date.from(now.minus(90, ChronoUnit.SECONDS)))
                .compact();

        assertThatThrownBy(() -> validator.validate(token).block())
                .isInstanceOf(JwtException.class);
    }

    @Test
    void acceptsNotBeforeWithinClockSkew() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        Instant now = Instant.now();
        String token = tokenBuilder(KEY_ID, ISSUER, signingKeys)
                .subject(UUID.randomUUID().toString())
                .notBefore(Date.from(now.plus(10, ChronoUnit.SECONDS)))
                .compact();

        AuthenticatedUser user = validator.validate(token).block();

        assertThat(user).isNotNull();
    }

    @Test
    void rejectsNotBeforeBeyondClockSkew() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        Instant now = Instant.now();
        String token = tokenBuilder(KEY_ID, ISSUER, signingKeys)
                .subject(UUID.randomUUID().toString())
                .notBefore(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .compact();

        assertThatThrownBy(() -> validator.validate(token).block())
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithDifferentPrivateKey() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        String token = tokenBuilder(KEY_ID, ISSUER, otherKeys)
                .subject(UUID.randomUUID().toString())
                .compact();

        assertThatThrownBy(() -> validator.validate(token).block())
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnknownKid() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        String token = tokenBuilder("unknown-key", ISSUER, signingKeys)
                .subject(UUID.randomUUID().toString())
                .compact();

        assertThatThrownBy(() -> validator.validate(token).block())
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenWithWrongIssuer() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        String token = tokenBuilder(KEY_ID, "evil-issuer", signingKeys)
                .subject(UUID.randomUUID().toString())
                .compact();

        assertThatThrownBy(() -> validator.validate(token).block())
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsNonRs256Header() {
        JwtTokenValidator validator = validator(keyResolver(KEY_ID, signingKeys));
        assertThatThrownBy(() -> validator.validate("eyJhbGciOiJIUzI1NiIsImtpZCI6InRlc3Qta2V5In0.e30.x").block())
                .isInstanceOf(JwtException.class);
    }

    private static JwtTokenValidator validator(JwksKeyResolver resolver) {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer(ISSUER);
        properties.setAudience(AUDIENCE);
        properties.setClockSkewSeconds(CLOCK_SKEW_SECONDS);
        properties.setJwksUri("http://unused.test/jwks");
        return new JwtTokenValidator(
                properties, resolver, new ObjectMapper().findAndRegisterModules());
    }

    private static JwksKeyResolver keyResolver(String keyId, KeyPair keyPair) {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        return requested -> keyId.equals(requested)
                ? Mono.just(publicKey)
                : Mono.error(new JwtException("Unknown JWT key id"));
    }

    private static io.jsonwebtoken.JwtBuilder tokenBuilder(
            String keyId, String issuer, KeyPair keyPair) {
        return tokenBuilderWithoutAudience(keyId, issuer, keyPair)
                .audience().add(AUDIENCE).and();
    }

    private static io.jsonwebtoken.JwtBuilder tokenBuilderWithoutAudience(
            String keyId, String issuer, KeyPair keyPair) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(keyId).and()
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256);
    }
}
