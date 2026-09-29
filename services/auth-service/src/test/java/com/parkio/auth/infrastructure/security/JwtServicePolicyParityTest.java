package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.shared.AuthPrincipal;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PA-14: auth-service verifier parity with gateway critical JWT policy
 * (issuer, audience, RS256, kid, expiry, signature).
 */
class JwtServicePolicyParityTest {

    private static final String ISSUER = "parkio-auth-test";
    private static final String AUDIENCE = "parkio-api-test";
    private static final String KEY_ID = "parity-key";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Clock clock;
    private KeyPair keyPair;
    private JwtService jwtService;

    @BeforeEach
    void setUp() throws Exception {
        clock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC);
        keyPair = rsa();
        JwtProperties props = new JwtProperties();
        props.setKeyId(KEY_ID);
        props.setPrivateKeyPem(privateKeyPem(keyPair));
        props.setIssuer(ISSUER);
        props.setAudience(AUDIENCE);
        props.setAccessTokenTtl(Duration.ofMinutes(15));
        RsaKeyProvider provider = new RsaKeyProvider(props);
        jwtService = new JwtService(props, provider, clock, MAPPER);
    }

    @Test
    void jwtB_currentIssuedTokenAcceptedByAuthVerifier() {
        String token = jwtService.issue(user()).token();
        AuthPrincipal principal = jwtService.parse(token);
        assertThat(principal.email()).isEqualTo("rider@parkio.test");
    }

    @Test
    void jwtC_wrongAudienceRejected() {
        String token = baseBuilder()
                .audience().add("other-api").and()
                .compact();
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void jwtD_missingAudienceRejected() {
        Instant now = clock.instant();
        String token = Jwts.builder()
                .header().keyId(KEY_ID).and()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .claim("email", "rider@parkio.test")
                .claim("roles", List.of("USER"))
                .claim("status", "ACTIVE")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void jwtE_wrongIssuerRejected() {
        String token = baseBuilderWithoutIssuer()
                .issuer("evil-issuer")
                .audience().add(AUDIENCE).and()
                .compact();
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void jwtF_expiredTokenRejected() {
        Instant now = clock.instant();
        String token = Jwts.builder()
                .header().keyId(KEY_ID).and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "rider@parkio.test")
                .claim("roles", List.of("USER"))
                .claim("status", "ACTIVE")
                .issuedAt(Date.from(now.minus(2, ChronoUnit.HOURS)))
                .expiration(Date.from(now.minus(1, ChronoUnit.HOURS)))
                .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void jwtG_wrongAlgorithmRejected() {
        SecretKey hmac = new SecretKeySpec(
                "not-a-real-hmac-secret-for-alg-confusion-tests".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        Instant now = clock.instant();
        String token = Jwts.builder()
                .header().keyId(KEY_ID).and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith(hmac, Jwts.SIG.HS256)
                .compact();
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void jwtH_algNoneRejected() {
        // Malformed unsigned-style header with alg none — must fail closed.
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"kid\":\"parity-key\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        String token = header + "." + payload + ".";
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void jwtI_unknownKidRejected() {
        String token = Jwts.builder()
                .header().keyId("unknown-key").and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "rider@parkio.test")
                .claim("roles", List.of("USER"))
                .claim("status", "ACTIVE")
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(15, ChronoUnit.MINUTES)))
                .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void jwtJ_badSignatureRejected() throws Exception {
        KeyPair other = rsa();
        Instant now = clock.instant();
        String token = Jwts.builder()
                .header().keyId(KEY_ID).and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "rider@parkio.test")
                .claim("roles", List.of("USER"))
                .claim("status", "ACTIVE")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith((RSAPrivateKey) other.getPrivate(), Jwts.SIG.RS256)
                .compact();
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void jwtK_malformedJwtRejected() {
        assertThatThrownBy(() -> jwtService.parse("not-a-jwt"))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwtService.parse("a.b"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void issuedTokenContainsCanonicalAudienceAndRs256() throws Exception {
        String token = jwtService.issue(user()).token();
        var header = MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        var claims = MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(header.path("kid").asText()).isEqualTo(KEY_ID);
        assertThat(claims.path("iss").asText()).isEqualTo(ISSUER);
        if (claims.path("aud").isArray()) {
            assertThat(claims.path("aud").get(0).asText()).isEqualTo(AUDIENCE);
        } else {
            assertThat(claims.path("aud").asText()).isEqualTo(AUDIENCE);
        }
    }

    private io.jsonwebtoken.JwtBuilder baseBuilder() {
        Instant now = clock.instant();
        return Jwts.builder()
                .header().keyId(KEY_ID).and()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .claim("email", "rider@parkio.test")
                .claim("roles", List.of("USER"))
                .claim("status", "ACTIVE")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256);
    }

    private io.jsonwebtoken.JwtBuilder baseBuilderWithoutIssuer() {
        Instant now = clock.instant();
        return Jwts.builder()
                .header().keyId(KEY_ID).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "rider@parkio.test")
                .claim("roles", List.of("USER"))
                .claim("status", "ACTIVE")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256);
    }

    private static AuthUser user() {
        return new AuthUser(
                UUID.randomUUID(),
                "rider@parkio.test",
                "unused-hash",
                AuthUserStatus.ACTIVE,
                null,
                Set.of(new Role(UUID.randomUUID(), RoleName.USER)),
                Instant.parse("2026-06-09T00:00:00Z"),
                null);
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String privateKeyPem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----";
    }
}
