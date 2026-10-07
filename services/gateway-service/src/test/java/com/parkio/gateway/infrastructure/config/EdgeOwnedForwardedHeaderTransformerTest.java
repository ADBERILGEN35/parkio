package com.parkio.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

/** PR #306 review B1: a client-supplied {@code Forwarded} header must not choose the client address. */
class EdgeOwnedForwardedHeaderTransformerTest {

    private static final String EDGE = "172.18.0.2";
    private static final String CLIENT = "198.51.100.7";
    private static final String SPOOFED = "203.0.113.99";

    private final ClientIpResolver resolver = new ClientIpResolver(List.of("172.16.0.0/12"));

    @Test
    void theStockTransformerLetsAForwardedHeaderWinOverTheEdgesXForwardedFor() {
        ServerHttpRequest transformed = new ForwardedHeaderTransformer().apply(spoofed());
        // The reason this class exists: Spring prefers Forwarded, which Caddy passes through.
        assertThat(resolver.resolve(transformed)).isEqualTo(SPOOFED);
    }

    @Test
    void aClientSuppliedForwardedHeaderIsDroppedAndTheEdgesAddressWins() {
        ServerHttpRequest transformed = new EdgeOwnedForwardedHeaderTransformer().apply(spoofed());

        assertThat(transformed.getHeaders().containsKey("Forwarded")).isFalse();
        assertThat(transformed.getHeaders().containsKey("X-Forwarded-For")).isFalse();
        assertThat(resolver.resolve(transformed)).isEqualTo(CLIENT);
    }

    @Test
    void forwardedAloneDoesNotMoveThePeer() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(EDGE, 40000))
                .header("Forwarded", "for=" + SPOOFED)
                .build();
        ServerHttpRequest transformed = new EdgeOwnedForwardedHeaderTransformer().apply(request);

        assertThat(resolver.resolve(transformed)).isEqualTo(EDGE);
    }

    @Test
    void replacesSpringBootsTransformerUnderTheFrameworkStrategyOnly() {
        ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class))
                .withUserConfiguration(EdgeForwardedHeadersConfig.class);
        runner.withPropertyValues("server.forward-headers-strategy=framework").run(context -> {
            assertThat(context).hasSingleBean(ForwardedHeaderTransformer.class);
            assertThat(context.getBean("forwardedHeaderTransformer"))
                    .isInstanceOf(EdgeOwnedForwardedHeaderTransformer.class);
        });
        runner.run(context -> assertThat(context).doesNotHaveBean(ForwardedHeaderTransformer.class));
    }

    private static MockServerHttpRequest spoofed() {
        return MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(EDGE, 40000))
                .header("Forwarded", "for=" + SPOOFED)
                .header("X-Forwarded-For", CLIENT)
                .header("X-Forwarded-Proto", "https")
                .build();
    }
}
