package com.parkio.gateway.infrastructure.security;

import com.parkio.gateway.shared.GatewayHeaders;
import io.jsonwebtoken.JwtException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class RemoteJwksKeyResolver implements JwksKeyResolver {

    private final WebClient webClient;
    private final JwtProperties properties;
    private final Clock clock;

    private volatile CachedKeys cache;
    private Mono<CachedKeys> inFlightRefresh;

    public RemoteJwksKeyResolver(WebClient.Builder webClientBuilder,
                                 JwtProperties properties,
                                 Clock clock,
                                 @Value("${parkio.gateway.internal-secret}") String internalSecret) {
        this.webClient = webClientBuilder
                .defaultHeader(GatewayHeaders.GATEWAY_AUTH, internalSecret)
                .build();
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<RSAPublicKey> resolve(String keyId) {
        CachedKeys current = cache;
        Instant now = clock.instant();
        if (current != null && current.expiresAt().isAfter(now)) {
            RSAPublicKey key = current.keys().get(keyId);
            if (key != null) {
                return Mono.just(key);
            }
            return refresh(true).flatMap(keys -> keyOrError(keys, keyId));
        }
        return refresh(false).flatMap(keys -> keyOrError(keys, keyId));
    }

    private Mono<RSAPublicKey> keyOrError(CachedKeys keys, String keyId) {
        RSAPublicKey key = keys.keys().get(keyId);
        return key == null
                ? Mono.error(new JwtException("Unknown JWT key id"))
                : Mono.just(key);
    }

    private synchronized Mono<CachedKeys> refresh(boolean force) {
        CachedKeys current = cache;
        if (!force && current != null && current.expiresAt().isAfter(clock.instant())) {
            return Mono.just(current);
        }
        if (inFlightRefresh != null) {
            return inFlightRefresh;
        }

        inFlightRefresh = webClient.get()
                .uri(properties.getJwksUri())
                .retrieve()
                .bodyToMono(JwkSetResponse.class)
                .map(this::parse)
                .doOnNext(keys -> cache = keys)
                .doFinally(signal -> clearInFlight())
                .cache();
        return inFlightRefresh;
    }

    private synchronized void clearInFlight() {
        inFlightRefresh = null;
    }

    private CachedKeys parse(JwkSetResponse jwks) {
        if (jwks == null || jwks.keys() == null) {
            throw new JwtException("JWKS response is missing keys");
        }
        Map<String, RSAPublicKey> keys = jwks.keys().stream()
                .filter(this::isSigningKey)
                .collect(Collectors.toUnmodifiableMap(
                        JwkResponse::kid,
                        this::toPublicKey,
                        (first, duplicate) -> {
                            throw new JwtException("JWKS contains duplicate key ids");
                        }));
        if (keys.isEmpty()) {
            throw new JwtException("JWKS contains no RS256 signing keys");
        }
        return new CachedKeys(keys, clock.instant().plus(properties.getJwksCacheTtl()));
    }

    private boolean isSigningKey(JwkResponse jwk) {
        return jwk != null
                && "RSA".equals(jwk.kty())
                && "RS256".equals(jwk.alg())
                && "sig".equals(jwk.use())
                && jwk.kid() != null
                && !jwk.kid().isBlank()
                && jwk.n() != null
                && jwk.e() != null;
    }

    private RSAPublicKey toPublicKey(JwkResponse jwk) {
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            BigInteger modulus = new BigInteger(1, decoder.decode(jwk.n()));
            BigInteger exponent = new BigInteger(1, decoder.decode(jwk.e()));
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new JwtException("JWKS contains an invalid RSA public key", ex);
        }
    }

    private record CachedKeys(Map<String, RSAPublicKey> keys, Instant expiresAt) {
    }
}
