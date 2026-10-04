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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
        RemoteJwksKeyResolver resolver = new RemoteJwksKeyResolver(
                jwks(fetches, "known", key), properties(),
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

    @Test
    void forcedRefreshesWaitForTheCooldown() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger fetches = new AtomicInteger();
        RemoteJwksKeyResolver resolver = resolver(jwks(fetches, "known", rsaKey()), clock,
                bounds(Duration.ofSeconds(30), 100, Duration.ofHours(1), 1000, Duration.ofMinutes(5)));
        resolver.resolve("known").block();

        rejected(resolver, "forged-1");
        clock.advance(Duration.ofSeconds(29));
        rejected(resolver, "forged-2");
        assertThat(fetches).hasValue(2);

        clock.advance(Duration.ofSeconds(2));
        rejected(resolver, "forged-3");
        assertThat(fetches).hasValue(3);
    }

    @Test
    void theBudgetCapsForcedRefreshesPerWindow() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger fetches = new AtomicInteger();
        RemoteJwksKeyResolver resolver = resolver(jwks(fetches, "known", rsaKey()), clock,
                bounds(Duration.ofSeconds(1), 3, Duration.ofHours(1), 1000, Duration.ofMinutes(5)));
        resolver.resolve("known").block();

        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(2));
            rejected(resolver, "forged-" + i);
        }
        assertThat(fetches).hasValue(1 + 3);

        clock.advance(Duration.ofHours(1));
        resolver.resolve("known").block();
        assertThat(fetches).as("regular refresh after the cache expired").hasValue(5);
        rejected(resolver, "forged-next-window");
        assertThat(fetches).hasValue(6);
    }

    @Test
    void unknownKidsAreRememberedInABoundedCacheForTheirTtl() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger fetches = new AtomicInteger();
        RemoteJwksKeyResolver resolver = resolver(jwks(fetches, "known", rsaKey()), clock,
                bounds(Duration.ofSeconds(1), 1000, Duration.ofHours(1), 5, Duration.ofMinutes(5)));
        resolver.resolve("known").block();

        for (int i = 0; i < 20; i++) {
            clock.advance(Duration.ofSeconds(2));
            rejected(resolver, "forged-" + i);
        }
        assertThat(resolver.unknownKidCount()).isEqualTo(5);
        int afterFlood = fetches.get();

        clock.advance(Duration.ofSeconds(2));
        rejected(resolver, "forged-19");
        assertThat(fetches).as("a remembered kid does not refresh").hasValue(afterFlood);

        clock.advance(Duration.ofMinutes(5));
        rejected(resolver, "forged-19");
        assertThat(fetches).as("after its TTL it may refresh again").hasValue(afterFlood + 1);
    }

    @Test
    void aRotatedKeyIsAcceptedOnceTheCooldownHasPassed() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger fetches = new AtomicInteger();
        RSAPublicKey current = rsaKey();
        RSAPublicKey rotated = rsaKey();
        AtomicReference<String> body = new AtomicReference<>(keySet(entry("current", current)));
        RemoteJwksKeyResolver resolver = resolver(jwks(fetches, body::get), clock,
                bounds(Duration.ofSeconds(30), 20, Duration.ofHours(1), 1000, Duration.ofMinutes(5)));
        resolver.resolve("current").block();
        rejected(resolver, "forged");

        // auth-service rotates: the JWKS now publishes the new key.
        body.set(keySet(entry("current", current), entry("rotated", rotated)));
        clock.advance(Duration.ofSeconds(10));
        rejected(resolver, "rotated");

        clock.advance(Duration.ofSeconds(21));
        assertThat(resolver.resolve("rotated").block().getModulus()).isEqualTo(rotated.getModulus());
    }

    @Test
    void aRotatedKeyIsAcceptedAtTheNextRegularRefreshWhenTheBudgetIsSpent() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger fetches = new AtomicInteger();
        RSAPublicKey current = rsaKey();
        RSAPublicKey rotated = rsaKey();
        AtomicReference<String> body = new AtomicReference<>(keySet(entry("current", current)));
        RemoteJwksKeyResolver resolver = resolver(jwks(fetches, body::get), clock,
                bounds(Duration.ofSeconds(1), 1, Duration.ofHours(1), 1000, Duration.ofMinutes(5)));
        resolver.resolve("current").block();
        rejected(resolver, "forged");
        body.set(keySet(entry("current", current), entry("rotated", rotated)));

        clock.advance(Duration.ofMinutes(5));
        rejected(resolver, "rotated");

        clock.advance(Duration.ofMinutes(11));
        assertThat(resolver.resolve("rotated").block().getModulus()).isEqualTo(rotated.getModulus());
    }

    @Test
    void aJwksFetchThatNeverAnswersFailsAtTheFetchTimeout() {
        JwtProperties properties = properties();
        properties.setJwksFetchTimeout(Duration.ofMillis(200));
        RemoteJwksKeyResolver resolver = new RemoteJwksKeyResolver(
                WebClient.builder().exchangeFunction(request -> Mono.never()), properties,
                new MutableClock(), "internal-test-secret");

        long started = System.nanoTime();
        assertThatThrownBy(() -> resolver.resolve("known").block(Duration.ofSeconds(5)))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    private static void rejected(RemoteJwksKeyResolver resolver, String kid) {
        assertThatThrownBy(() -> resolver.resolve(kid).block()).as(kid).isInstanceOf(JwtException.class);
    }

    private static RemoteJwksKeyResolver resolver(WebClient.Builder builder, Clock clock, JwtProperties properties) {
        return new RemoteJwksKeyResolver(builder, properties, clock, "internal-test-secret");
    }

    private static JwtProperties properties() {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("parkio-auth");
        properties.setJwksUri("http://auth.test/jwks");
        properties.setJwksCacheTtl(Duration.ofMinutes(15));
        // Mocked exchanges answer at once, but the first exchange of a cold test JVM (codecs,
        // class loading) took over 5 s here; only the timeout test relies on the timeout.
        properties.setJwksFetchTimeout(Duration.ofSeconds(30));
        return properties;
    }

    private static JwtProperties bounds(Duration cooldown, int budget, Duration window, int negativeSize,
                                        Duration negativeTtl) {
        JwtProperties properties = properties();
        properties.setJwksRefreshCooldown(cooldown);
        properties.setJwksRefreshBudget(budget);
        properties.setJwksRefreshBudgetWindow(window);
        properties.setJwksNegativeCacheSize(negativeSize);
        properties.setJwksNegativeCacheTtl(negativeTtl);
        return properties;
    }

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-02T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    static String entry(String kid, RSAPublicKey key) {
        return """
                {"kty":"RSA","kid":"%s","use":"sig","alg":"RS256","n":"%s","e":"%s"}"""
                .formatted(kid, base64Url(key.getModulus()), base64Url(key.getPublicExponent()));
    }

    static String keySet(String... entries) {
        return "{\"keys\":[" + String.join(",", entries) + "]}";
    }

    static WebClient.Builder jwks(AtomicInteger fetches, String kid, RSAPublicKey key) {
        String body = keySet(entry(kid, key));
        return jwks(fetches, () -> body);
    }

    static WebClient.Builder jwks(AtomicInteger fetches, Supplier<String> body) {
        return WebClient.builder().exchangeFunction(request -> {
            fetches.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body.get())
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
