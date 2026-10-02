package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * U10 / CX-F05: a token with an unknown {@code kid} must not be able to make the gateway fetch
 * the JWKS. The authentication filter runs before route rate limits, so per-token forced
 * refreshes let anyone drive one auth-service request per forged token.
 */
class RemoteJwksKeyResolverRefreshBoundsTest {

    /** The default forced-refresh budget per window (configurable). */
    private static final int DEFAULT_BUDGET = 20;

    @Test
    void hundredDistinctUnknownKidsCannotDriveAFetchPerToken() throws Exception {
        RSAPublicKey key = rsaKey();
        AtomicInteger fetches = new AtomicInteger();
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("parkio-auth");
        properties.setJwksUri("http://auth.test/jwks");
        properties.setJwksCacheTtl(Duration.ofMinutes(15));
        RemoteJwksKeyResolver resolver = new RemoteJwksKeyResolver(
                jwks(fetches, "known", key), properties,
                Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC), "internal-test-secret");
        assertThat(resolver.resolve("known").block()).isNotNull();

        for (int i = 0; i < 100; i++) {
            String kid = "forged-" + i;
            assertThatThrownBy(() -> resolver.resolve(kid).block()).isInstanceOf(JwtException.class);
        }

        // One initial load plus at most the forced-refresh budget.
        assertThat(fetches.get()).isLessThanOrEqualTo(1 + DEFAULT_BUDGET);
        assertThat(resolver.resolve("known").block()).isNotNull();
    }

    static WebClient.Builder jwks(AtomicInteger fetches, String kid, RSAPublicKey key) {
        String body = """
                {"keys":[{"kty":"RSA","kid":"%s","use":"sig","alg":"RS256","n":"%s","e":"%s"}]}
                """.formatted(kid, base64Url(key.getModulus()), base64Url(key.getPublicExponent()));
        return WebClient.builder().exchangeFunction(request -> {
            fetches.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        });
    }

    static RSAPublicKey rsaKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return (RSAPublicKey) generator.generateKeyPair().getPublic();
    }

    static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(bytes, offset, bytes.length));
    }
}
