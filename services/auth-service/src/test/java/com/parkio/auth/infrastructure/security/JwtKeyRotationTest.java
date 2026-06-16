package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.presentation.JwksController;
import com.parkio.auth.presentation.dto.JwkResponse;
import com.parkio.auth.shared.AuthPrincipal;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * JWT key-rotation behaviour: auth signs only with the active key, but exposes (and
 * verifies against) previous public keys kept in {@code additional-public-keys-json}
 * during a rotation window, so access tokens issued before the rollover stay valid
 * until they expire.
 */
class JwtKeyRotationTest {

    private static final String ISSUER = "parkio-auth-test";
    private static final String AUDIENCE = "parkio-api-test";
    private static final String ACTIVE_KID = "active-key";
    private static final String PREVIOUS_KID = "previous-key";
    // System clock: JwtService.parse validates exp against the JVM clock, so tokens must be fresh.
    private static final Clock CLOCK = Clock.systemUTC();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void jwksExposesActiveAndPreviousKeysAndSignsOnlyWithActive() throws Exception {
        KeyPair active = rsa();
        KeyPair previous = rsa();
        RsaKeyProvider provider = providerWith(active, previous);
        JwtService jwtService = new JwtService(properties(), provider, CLOCK);

        // JWKS contains both kids, all public (no private material).
        List<JwkResponse> jwks = new JwksController(provider).jwks().keys();
        assertThat(jwks).extracting(JwkResponse::kid).containsExactlyInAnyOrder(ACTIVE_KID, PREVIOUS_KID);
        assertThat(jwks).allSatisfy(jwk -> {
            assertThat(jwk.alg()).isEqualTo("RS256");
            assertThat(jwk.use()).isEqualTo("sig");
            assertThat(jwk.kty()).isEqualTo("RSA");
        });

        // Newly issued tokens are signed only with the active kid.
        String issued = jwtService.issue(user()).token();
        assertThat(header(issued).path("kid").asText()).isEqualTo(ACTIVE_KID);
        AuthPrincipal principal = jwtService.parse(issued);
        assertThat(principal.email()).isEqualTo("rider@parkio.test");
    }

    @Test
    void acceptsTokenSignedByPreviousKeyDuringRotation() throws Exception {
        KeyPair active = rsa();
        KeyPair previous = rsa();
        RsaKeyProvider provider = providerWith(active, previous);
        JwtService jwtService = new JwtService(properties(), provider, CLOCK);

        // A token signed by the *previous* key (kid) must still verify (old token, mid-rotation).
        String oldToken = tokenSignedBy(previous, PREVIOUS_KID);
        AuthPrincipal principal = jwtService.parse(oldToken);
        assertThat(principal.email()).isEqualTo("rider@parkio.test");
    }

    @Test
    void rejectsTokenSignedByUnknownKey() throws Exception {
        KeyPair active = rsa();
        KeyPair previous = rsa();
        RsaKeyProvider provider = providerWith(active, previous);
        JwtService jwtService = new JwtService(properties(), provider, CLOCK);

        KeyPair stranger = rsa();
        String forged = tokenSignedBy(stranger, "attacker-key");
        assertThatThrownBy(() -> jwtService.parse(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void failsClosedWhenAdditionalKeyDuplicatesActiveKid() throws Exception {
        KeyPair active = rsa();
        JwtProperties props = baseProperties(active);
        props.setKeyId(ACTIVE_KID);
        props.setAdditionalPublicKeysJson(additionalJson(ACTIVE_KID, active));

        assertThatThrownBy(() -> new RsaKeyProvider(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void failsClosedOnMalformedAdditionalJson() throws Exception {
        KeyPair active = rsa();
        JwtProperties props = baseProperties(active);
        props.setAdditionalPublicKeysJson("not-json");

        assertThatThrownBy(() -> new RsaKeyProvider(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("additional-public-keys-json");
    }

    private RsaKeyProvider providerWith(KeyPair active, KeyPair previous) throws Exception {
        JwtProperties props = baseProperties(active);
        props.setAdditionalPublicKeysJson(additionalJson(PREVIOUS_KID, previous));
        return new RsaKeyProvider(props);
    }

    private JwtProperties baseProperties(KeyPair active) {
        JwtProperties props = new JwtProperties();
        props.setKeyId(ACTIVE_KID);
        props.setPrivateKeyPem(privateKeyPem(active));
        props.setIssuer(ISSUER);
        props.setAudience(AUDIENCE);
        props.setAccessTokenTtl(Duration.ofMinutes(15));
        return props;
    }

    private JwtProperties properties() {
        JwtProperties props = new JwtProperties();
        props.setIssuer(ISSUER);
        props.setAudience(AUDIENCE);
        props.setAccessTokenTtl(Duration.ofMinutes(15));
        return props;
    }

    private String tokenSignedBy(KeyPair pair, String kid) {
        Instant now = CLOCK.instant();
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "rider@parkio.test")
                .claim("roles", List.of("USER"))
                .claim("status", "ACTIVE")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .signWith(pair.getPrivate(), Jwts.SIG.RS256)
                .compact();
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

    private static String additionalJson(String kid, KeyPair pair) {
        try {
            return MAPPER.writeValueAsString(List.of(Map.of("kid", kid, "pem", publicKeyPem(pair))));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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

    private static String publicKeyPem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----";
    }

    private static com.fasterxml.jackson.databind.JsonNode header(String token) throws Exception {
        String segment = token.split("\\.")[0];
        return MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
    }
}
