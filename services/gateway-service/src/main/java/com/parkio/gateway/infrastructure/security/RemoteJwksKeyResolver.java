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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
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

    /*
     * Refreshes forced by an unknown kid (U10 / CX-F05). The authentication filter runs before
     * route rate limits, so a forged kid must not buy a JWKS fetch: a forced refresh needs the
     * cooldown to have passed and budget left in the current window, and kids that a fetch
     * did not find are remembered for a while in a bounded cache. Guarded by "this".
     */
    private Instant lastForcedRefresh;
    private Instant budgetWindowStart;
    private int forcedRefreshesInWindow;
    private final Map<String, Instant> unknownKids;

    public RemoteJwksKeyResolver(WebClient.Builder webClientBuilder,
                                 JwtProperties properties,
                                 Clock clock,
                                 @Value("${parkio.gateway.internal-secret}") String internalSecret) {
        this.webClient = webClientBuilder
                .defaultHeader(GatewayHeaders.GATEWAY_AUTH, internalSecret)
                .build();
        this.properties = properties;
        this.clock = clock;
        int negativeCacheSize = properties.getJwksNegativeCacheSize();
        this.unknownKids = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
                return size() > negativeCacheSize;
            }
        };
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
            Mono<CachedKeys> forced = forcedRefresh(keyId, now);
            if (forced == null) {
                return Mono.error(new JwtException("Unknown JWT key id"));
            }
            return forced.flatMap(keys -> keyOrError(keys, keyId));
        }
        return refresh().flatMap(keys -> keyOrError(keys, keyId));
    }

    private Mono<RSAPublicKey> keyOrError(CachedKeys keys, String keyId) {
        RSAPublicKey key = keys.keys().get(keyId);
        if (key == null) {
            rememberUnknown(keyId);
            return Mono.error(new JwtException("Unknown JWT key id"));
        }
        return Mono.just(key);
    }

    /**
     * The refresh an unknown kid may force now, or {@code null} when it may not. Joining a
     * refresh already in flight is free; otherwise the kid must not be remembered as unknown,
     * the cooldown must have passed and the window must have budget left.
     *
     * <p>The decision, the cooldown and budget charge, and joining or reserving the single
     * in-flight fetch happen in one step under the monitor. A completing fetch clears the
     * in-flight refresh under the same monitor, so a request admitted to join can never start
     * a fetch of its own (review finding F1). The returned fetch is cold: nothing is sent
     * until the caller subscribes, outside the lock.
     */
    private synchronized Mono<CachedKeys> forcedRefresh(String keyId, Instant now) {
        Instant unknownUntil = unknownKids.get(keyId);
        if (unknownUntil != null) {
            if (unknownUntil.isAfter(now)) {
                return null;
            }
            unknownKids.remove(keyId);
        }
        if (inFlightRefresh != null) {
            return inFlightRefresh;
        }
        if (lastForcedRefresh != null && lastForcedRefresh.plus(properties.getJwksRefreshCooldown()).isAfter(now)) {
            return null;
        }
        if (budgetWindowStart == null
                || !budgetWindowStart.plus(properties.getJwksRefreshBudgetWindow()).isAfter(now)) {
            budgetWindowStart = now;
            forcedRefreshesInWindow = 0;
        }
        if (forcedRefreshesInWindow >= properties.getJwksRefreshBudget()) {
            return null;
        }
        forcedRefreshesInWindow++;
        lastForcedRefresh = now;
        return startFetch();
    }

    private synchronized void rememberUnknown(String keyId) {
        unknownKids.put(keyId, clock.instant().plus(properties.getJwksNegativeCacheTtl()));
    }

    /** Number of kids currently remembered as unknown (bounded by the negative-cache size). */
    synchronized int unknownKidCount() {
        return unknownKids.size();
    }

    /** Regular refresh: only when the cache is missing or expired, joining a fetch in flight. */
    private synchronized Mono<CachedKeys> refresh() {
        CachedKeys current = cache;
        if (current != null && current.expiresAt().isAfter(clock.instant())) {
            return Mono.just(current);
        }
        if (inFlightRefresh != null) {
            return inFlightRefresh;
        }
        return startFetch();
    }

    /**
     * Reserves the single in-flight fetch. The caller holds the monitor; the request is sent
     * when the returned (cached) Mono is first subscribed, so no I/O happens under the lock.
     */
    private Mono<CachedKeys> startFetch() {
        inFlightRefresh = webClient.get()
                .uri(properties.getJwksUri())
                .retrieve()
                .bodyToMono(JwkSetResponse.class)
                .timeout(properties.getJwksFetchTimeout())
                .onErrorMap(TimeoutException.class, ex -> new JwtException("JWKS fetch timed out", ex))
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
