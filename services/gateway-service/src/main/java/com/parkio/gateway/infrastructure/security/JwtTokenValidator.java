package com.parkio.gateway.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Verifies RS256 access tokens with auth-service public keys resolved from JWKS.
 * The untrusted JWT header is read only to select a key; signature, issuer and
 * expiry are then validated by JJWT.
 */
@Component
public class JwtTokenValidator {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_STATUS = "status";

    private final String issuer;
    private final JwksKeyResolver keys;
    private final ObjectMapper objectMapper;

    public JwtTokenValidator(JwtProperties properties,
                             JwksKeyResolver keys,
                             ObjectMapper objectMapper) {
        this.issuer = properties.getIssuer();
        this.keys = keys;
        this.objectMapper = objectMapper;
    }

    /**
     * Verifies the token and returns the authenticated identity.
     *
     * @throws JwtException if the token is missing required structure, has a bad
     *                      signature, a wrong issuer, is expired, or is otherwise
     *                      invalid.
     */
    @SuppressWarnings("unchecked")
    public Mono<AuthenticatedUser> validate(String token) {
        try {
            String keyId = requiredKeyId(token);
            return keys.resolve(keyId).map(publicKey -> parse(token, publicKey));
        } catch (JwtException | IllegalArgumentException ex) {
            return Mono.error(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private AuthenticatedUser parse(String token, java.security.interfaces.RSAPublicKey publicKey) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(publicKey)
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

    private String requiredKeyId(String token) {
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) {
            throw new JwtException("JWT must contain three segments");
        }
        try {
            JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(segments[0]));
            if (!"RS256".equals(header.path("alg").asText())) {
                throw new JwtException("JWT algorithm must be RS256");
            }
            String keyId = header.path("kid").asText();
            if (keyId.isBlank()) {
                throw new JwtException("JWT is missing the key id");
            }
            return keyId;
        } catch (IOException | IllegalArgumentException ex) {
            throw new JwtException("JWT header is invalid", ex);
        }
    }
}
