package com.parkio.auth.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.port.AccessTokenIssuer;
import com.parkio.auth.application.result.IssuedAccessToken;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.Role;
import com.parkio.auth.shared.AuthPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import java.io.IOException;
import java.security.Key;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies RS256 JWT access tokens. Claims: {@code iss}, {@code aud},
 * {@code sub} (user id), {@code email}, {@code roles}, {@code status}, {@code iat},
 * {@code exp}. Implements {@link AccessTokenIssuer}; the bearer filter uses
 * {@link #parse}.
 *
 * <p>Verification policy is aligned with gateway {@code JwtTokenValidator}:
 * signature via kid, issuer, audience, explicit RS256 header, and expiry.
 */
@Component
public class JwtService implements AccessTokenIssuer {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_STATUS = "status";
    private static final String CLAIM_SESSION_EPOCH = "session_epoch";
    private static final String REQUIRED_ALG = "RS256";

    private final RsaKeyProvider keys;
    private final String issuer;
    private final String audience;
    private final java.time.Duration accessTokenTtl;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public JwtService(JwtProperties properties, RsaKeyProvider keys, Clock clock, ObjectMapper objectMapper) {
        this.keys = keys;
        this.issuer = properties.getIssuer();
        this.audience = properties.getAudience();
        this.accessTokenTtl = properties.getAccessTokenTtl();
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public IssuedAccessToken issue(AuthUser user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        List<String> roles = user.roles().stream()
                .map(Role::name)
                .map(Enum::name)
                .toList();

        String token = Jwts.builder()
                .header().keyId(keys.keyId()).and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(user.id().toString())
                .claim(CLAIM_EMAIL, user.email())
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_STATUS, user.status().name())
                .claim(CLAIM_SESSION_EPOCH, user.sessionEpoch())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(keys.privateKey(), Jwts.SIG.RS256)
                .compact();

        return new IssuedAccessToken(token, expiresAt);
    }

    /**
     * Verifies signature, RS256 algorithm, issuer, audience and expiry, then
     * extracts the authenticated principal. Throws {@link JwtException} on any
     * invalid token.
     */
    @SuppressWarnings("unchecked")
    public AuthPrincipal parse(String token) {
        requireRs256KeyId(token);

        Jws<Claims> jws = Jwts.parser()
                .keyLocator(verificationKeyLocator())
                .requireIssuer(issuer)
                .requireAudience(audience)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token);

        Claims claims = jws.getPayload();
        UUID userId = UUID.fromString(claims.getSubject());
        String email = claims.get(CLAIM_EMAIL, String.class);
        List<String> roles = claims.get(CLAIM_ROLES, List.class);
        String status = claims.get(CLAIM_STATUS, String.class);
        return new AuthPrincipal(userId, email, roles == null ? List.of() : roles, status);
    }

    /**
     * Untrusted-header gate matching gateway {@code JwtTokenValidator#requiredKeyId}:
     * three segments, {@code alg=RS256}, non-blank {@code kid}.
     */
    private void requireRs256KeyId(String token) {
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) {
            throw new JwtException("JWT must contain three segments");
        }
        try {
            JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(segments[0]));
            if (!REQUIRED_ALG.equals(header.path("alg").asText())) {
                throw new JwtException("JWT algorithm must be RS256");
            }
            String keyId = header.path("kid").asText();
            if (keyId == null || keyId.isBlank()) {
                throw new JwtException("JWT is missing the key id");
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new JwtException("JWT header is invalid", ex);
        }
    }

    /**
     * Selects the verification key by the token's {@code kid} from the active +
     * previous (rotation) public keys. A token whose {@code kid} is unknown (or
     * absent) is rejected — fail-closed, no silent fallback that could let an
     * unverifiable token through.
     */
    private Locator<Key> verificationKeyLocator() {
        Map<String, RSAPublicKey> verificationKeys = keys.verificationKeys();
        return (Header header) -> {
            String kid = header instanceof ProtectedHeader protectedHeader
                    ? protectedHeader.getKeyId()
                    : null;
            if (kid == null) {
                throw new JwtException("Access token is missing a key id (kid)");
            }
            RSAPublicKey key = verificationKeys.get(kid);
            if (key == null) {
                throw new JwtException("Unknown JWT key id");
            }
            return key;
        };
    }
}
