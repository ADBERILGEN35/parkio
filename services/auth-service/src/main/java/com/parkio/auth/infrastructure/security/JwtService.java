package com.parkio.auth.infrastructure.security;

import com.parkio.auth.application.port.AccessTokenIssuer;
import com.parkio.auth.application.result.IssuedAccessToken;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.Role;
import com.parkio.auth.shared.AuthPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies RS256 JWT access tokens. Claims: {@code iss}, {@code aud},
 * {@code sub} (user id), {@code email}, {@code roles}, {@code status}, {@code iat},
 * {@code exp}. Implements {@link AccessTokenIssuer}; the bearer filter uses
 * {@link #parse}.
 */
@Component
public class JwtService implements AccessTokenIssuer {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_STATUS = "status";

    private final RsaKeyProvider keys;
    private final String issuer;
    private final String audience;
    private final java.time.Duration accessTokenTtl;
    private final Clock clock;

    public JwtService(JwtProperties properties, RsaKeyProvider keys, Clock clock) {
        this.keys = keys;
        this.issuer = properties.getIssuer();
        this.audience = properties.getAudience();
        this.accessTokenTtl = properties.getAccessTokenTtl();
        this.clock = clock;
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
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(keys.privateKey(), Jwts.SIG.RS256)
                .compact();

        return new IssuedAccessToken(token, expiresAt);
    }

    /**
     * Verifies the token's signature, issuer and expiry and extracts the
     * authenticated principal. Throws {@link io.jsonwebtoken.JwtException} on any
     * invalid token.
     */
    @SuppressWarnings("unchecked")
    public AuthPrincipal parse(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(keys.publicKey())
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token);

        Claims claims = jws.getPayload();
        UUID userId = UUID.fromString(claims.getSubject());
        String email = claims.get(CLAIM_EMAIL, String.class);
        List<String> roles = claims.get(CLAIM_ROLES, List.class);
        String status = claims.get(CLAIM_STATUS, String.class);
        return new AuthPrincipal(userId, email, roles == null ? List.of() : roles, status);
    }
}
