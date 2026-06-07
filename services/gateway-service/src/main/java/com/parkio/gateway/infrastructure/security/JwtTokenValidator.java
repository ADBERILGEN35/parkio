package com.parkio.gateway.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Verifies HS256 access tokens at the edge using the same signing contract as
 * auth-service: signature, issuer and expiry are all checked, and the {@code sub},
 * {@code email}, {@code roles} and {@code status} claims are extracted.
 *
 * <p>TODO(security-backlog): before wider rollout, migrate the platform to an
 * asymmetric signature (RS256/ES256) with key distribution via a JWKS endpoint, so
 * the gateway verifies with a public key and only auth-service holds the private
 * signing key. A shared symmetric secret means any service holding it could mint
 * tokens. Coordinate the change with auth-service so both sides switch together.
 */
@Component
public class JwtTokenValidator {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_STATUS = "status";

    private final SecretKey signingKey;
    private final String issuer;

    public JwtTokenValidator(JwtProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.issuer = properties.getIssuer();
    }

    /**
     * Verifies the token and returns the authenticated identity.
     *
     * @throws JwtException if the token is missing required structure, has a bad
     *                      signature, a wrong issuer, is expired, or is otherwise
     *                      invalid.
     */
    @SuppressWarnings("unchecked")
    public AuthenticatedUser validate(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token);

        Claims claims = jws.getPayload();
        String userId = claims.getSubject();
        if (userId == null || userId.isBlank()) {
            throw new JwtException("Token is missing the subject claim");
        }
        String email = claims.get(CLAIM_EMAIL, String.class);
        List<String> roles = claims.get(CLAIM_ROLES, List.class);
        String status = claims.get(CLAIM_STATUS, String.class);
        return new AuthenticatedUser(userId, email, roles == null ? List.of() : List.copyOf(roles), status);
    }
}
