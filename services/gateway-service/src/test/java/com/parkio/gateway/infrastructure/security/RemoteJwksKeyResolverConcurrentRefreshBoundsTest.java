package com.parkio.gateway.infrastructure.security;

import static com.parkio.gateway.infrastructure.security.RemoteJwksKeyResolverRefreshBoundsTest.entry;
import static com.parkio.gateway.infrastructure.security.RemoteJwksKeyResolverRefreshBoundsTest.keySet;
import static com.parkio.gateway.infrastructure.security.RemoteJwksKeyResolverRefreshBoundsTest.rsaKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * U10 / CX-F05 under concurrency. The gateway resolves keys on its event loop without blocking,
 * so many requests with forged kids are in {@code resolve()} at the same time as a JWKS fetch
 * completes. A request that joins an in-flight forced refresh must never start a fetch of its
 * own: the bound is one initial load plus the forced-refresh budget, exactly.
 */
class RemoteJwksKeyResolverConcurrentRefreshBoundsTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 10;
    private static final Duration ROUND_TRAFFIC = Duration.ofMillis(400);

    /**
     * Budget 1, a clock that never moves (the cooldown never passes, the window never resets
     * and the cache never expires): exactly 2 fetches are allowed, the initial load and one
     * forced refresh. Every round floods distinct forged kids from several threads, subscribing
     * without blocking, while the JWKS answers asynchronously.
     */
    @Test
    void nonBlockingConcurrentForgedKidsCannotExceedTheExactFetchBound() throws Exception {
        List<Integer> fetchesPerRound = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            fetchesPerRound.add(floodRound(round));
        }
        assertThat(fetchesPerRound).as("JWKS fetches per round (initial load + budget 1)")
                .allSatisfy(fetches -> assertThat(fetches).isEqualTo(2));
    }

    /**
     * A request admitted to join the in-flight forced refresh, whose fetch then completes
     * before the request subscribes, gets that fetch's result and starts nothing. After the
     * completion, the cooldown and the spent budget refuse new forced refreshes.
     */
    @Test
    void aJoinAdmittedBeforeTheFetchCompletesNeverStartsAnotherFetch() throws Exception {
        RSAPublicKey known = rsaKey();
        ControlledJwks jwks = new ControlledJwks();
        RemoteJwksKeyResolver resolver = new RemoteJwksKeyResolver(jwks.builder(), budgetOne(),
                new RemoteJwksKeyResolverRefreshBoundsTest.MutableClock(), "internal-test-secret");

        Mono<RSAPublicKey> initial = resolver.resolve("known");
        CompletableFuture<RSAPublicKey> initialResult = initial.toFuture();
        jwks.answer(0, keySet(entry("known", known)));
        assertThat(initialResult.get(30, TimeUnit.SECONDS)).isNotNull();

        // Admitted and charged: the forced refresh is reserved, but nothing is sent until the
        // caller subscribes (no I/O under the resolver's lock).
        Mono<RSAPublicKey> reserved = resolver.resolve("forged-a");
        assertThat(jwks.started()).isEqualTo(1);
        CompletableFuture<RSAPublicKey> charged = reserved.toFuture();
        assertThat(jwks.started()).isEqualTo(2);

        // Admitted while that refresh is in flight: a free join.
        Mono<RSAPublicKey> joined = resolver.resolve("forged-b");

        // The in-flight refresh completes between the join's admission and its subscription.
        jwks.answer(1, keySet(entry("known", known)));
        assertThatThrownBy(() -> charged.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(JwtException.class);

        assertThatThrownBy(() -> joined.block(Duration.ofSeconds(30))).isInstanceOf(JwtException.class);
        assertThat(jwks.started()).as("the join replays the completed refresh").isEqualTo(2);

        // Cooldown and budget refuse the next forced refresh; the known key needs no fetch.
        assertThatThrownBy(() -> resolver.resolve("forged-c").block(Duration.ofSeconds(30)))
                .isInstanceOf(JwtException.class);
        assertThat(resolver.resolve("known").block(Duration.ofSeconds(30))).isNotNull();
        assertThat(jwks.started()).isEqualTo(2);
    }

    private int floodRound(int round) throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        String body = keySet(entry("known", rsaKey()));
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            fetches.incrementAndGet();
            // The answer arrives on another thread, as it does over the network.
            return Mono.just(ok(body)).delayElement(Duration.ofMillis(1));
        });
        RemoteJwksKeyResolver resolver = new RemoteJwksKeyResolver(builder, budgetOne(),
                new RemoteJwksKeyResolverRefreshBoundsTest.MutableClock(), "internal-test-secret");
        assertThat(resolver.resolve("known").block(Duration.ofSeconds(30))).isNotNull();

        // One extra count for the producers, released once they are done, so that "drained"
        // completes only after every subscribed request has terminated.
        AtomicLong outstanding = new AtomicLong(1);
        CompletableFuture<Void> drained = new CompletableFuture<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> producers = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int id = t;
            Thread producer = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long deadline = System.nanoTime() + ROUND_TRAFFIC.toNanos();
                for (long i = 0; System.nanoTime() < deadline; i++) {
                    outstanding.incrementAndGet();
                    resolver.resolve("forged-" + round + "-" + id + "-" + i)
                            .doFinally(signal -> {
                                if (outstanding.decrementAndGet() == 0) {
                                    drained.complete(null);
                                }
                            })
                            .subscribe(
                                    key -> unexpected.add(new AssertionError("forged kid resolved")),
                                    error -> {
                                        if (!(error instanceof JwtException)) {
                                            unexpected.add(error);
                                        }
                                    });
                }
            }, "forged-kid-producer-" + t);
            producers.add(producer);
            producer.start();
        }
        start.countDown();
        for (Thread producer : producers) {
            producer.join();
        }
        if (outstanding.decrementAndGet() == 0) {
            drained.complete(null);
        }
        drained.get(30, TimeUnit.SECONDS);

        assertThat(unexpected).as("every forged kid is rejected with JwtException").isEmpty();
        assertThat(resolver.resolve("known").block(Duration.ofSeconds(30))).isNotNull();
        return fetches.get();
    }

    private static JwtProperties budgetOne() {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("parkio-auth");
        properties.setJwksUri("http://auth.test/jwks");
        properties.setJwksCacheTtl(Duration.ofMinutes(15));
        properties.setJwksFetchTimeout(Duration.ofSeconds(30));
        properties.setJwksRefreshCooldown(Duration.ofHours(1));
        properties.setJwksRefreshBudget(1);
        properties.setJwksRefreshBudgetWindow(Duration.ofHours(1));
        properties.setJwksNegativeCacheSize(1000);
        properties.setJwksNegativeCacheTtl(Duration.ofMinutes(5));
        return properties;
    }

    private static ClientResponse ok(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    /** A JWKS endpoint whose answers the test releases one fetch at a time. */
    private static final class ControlledJwks {
        private final List<Sinks.One<ClientResponse>> fetches = new CopyOnWriteArrayList<>();

        WebClient.Builder builder() {
            return WebClient.builder().exchangeFunction(request -> {
                Sinks.One<ClientResponse> answer = Sinks.one();
                fetches.add(answer);
                return answer.asMono();
            });
        }

        int started() {
            return fetches.size();
        }

        void answer(int fetch, String body) {
            assertThat(fetches).as("fetch %d started", fetch).hasSizeGreaterThan(fetch);
            fetches.get(fetch).tryEmitValue(ok(body)).orThrow();
        }
    }
}
