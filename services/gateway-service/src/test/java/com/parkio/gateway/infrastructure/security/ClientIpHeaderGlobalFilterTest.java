package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.infrastructure.config.ClientIpResolver;
import com.parkio.gateway.shared.GatewayHeaders;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;
import reactor.core.publisher.Mono;

/**
 * CL-F15: the gateway owns {@code X-Parkio-Client-Ip}. A client cannot set it, the value
 * is what {@link ClientIpResolver} resolves, and under the hosted-beta forwarding strategy
 * it is the edge-observed client.
 */
class ClientIpHeaderGlobalFilterTest {

    private static final String EDGE_PROXY = "172.18.0.2";
    private static final String CLIENT = "198.51.100.7";

    private final ClientIpHeaderGlobalFilter filter =
            new ClientIpHeaderGlobalFilter(new ClientIpResolver(List.of("172.16.0.0/12")));

    @Test
    void stripsASpoofedHeaderAndInjectsThePeerAddress() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(CLIENT, 51234))
                .header(GatewayHeaders.CLIENT_IP, "203.0.113.250")
                .build();
        CapturingChain chain = run(request);

        assertThat(forwarded(chain)).isEqualTo(CLIENT);
    }

    @Test
    void ignoresForwardedForFromAnUntrustedPeer() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(CLIENT, 51234))
                .header("X-Forwarded-For", "203.0.113.250")
                .build();
        CapturingChain chain = run(request);

        assertThat(forwarded(chain)).isEqualTo(CLIENT);
    }

    @Test
    void usesTheRightMostUntrustedForwardedForHopBehindATrustedProxy() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(EDGE_PROXY, 40000))
                .header("X-Forwarded-For", "203.0.113.250, " + CLIENT)
                .build();
        CapturingChain chain = run(request);

        assertThat(forwarded(chain)).isEqualTo(CLIENT);
    }

    @Test
    void leavesTheHeaderAbsentWhenNoAddressIsKnown() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login")
                .header(GatewayHeaders.CLIENT_IP, "203.0.113.250")
                .build();
        CapturingChain chain = run(request);

        assertThat(chain.captured.getRequest().getHeaders().containsKey(GatewayHeaders.CLIENT_IP)).isFalse();
    }

    /**
     * P3 engineering check. Hosted-beta runs {@code server.forward-headers-strategy=framework}
     * (#161): Spring's {@link ForwardedHeaderTransformer} runs before any filter, replaces the
     * peer address with the first {@code X-Forwarded-For} entry and removes the forwarding
     * headers. Behind Caddy, which replaces that header with the connecting client's address,
     * the resolved value is therefore the edge-observed client. The trusted-proxy list plays
     * no part in that mode: the header is gone before {@link ClientIpResolver} sees it.
     */
    @Test
    void underTheFrameworkStrategyTheResolvedIpIsTheEdgeObservedClient() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(EDGE_PROXY, 40000))
                .header("X-Forwarded-For", CLIENT)
                .header("X-Forwarded-Proto", "https")
                .header(GatewayHeaders.CLIENT_IP, "203.0.113.250")
                .build();
        ServerHttpRequest transformed = new ForwardedHeaderTransformer().apply(request);
        assertThat(transformed.getHeaders().containsKey("X-Forwarded-For")).isFalse();
        assertThat(transformed.getRemoteAddress()).isNotNull();
        // The transformer builds an unresolved address from the header: no InetAddress, only the host string.
        assertThat(transformed.getRemoteAddress().isUnresolved()).isTrue();
        assertThat(transformed.getRemoteAddress().getHostString()).isEqualTo(CLIENT);

        CapturingChain chain = new CapturingChain();
        filter.filter(MockServerWebExchange.from(request).mutate().request(transformed).build(), chain).block();

        assertThat(forwarded(chain)).isEqualTo(CLIENT);
    }

    /**
     * The flip side of the check above, recorded so the trust boundary is explicit: in the
     * framework mode the transformer trusts {@code X-Forwarded-For} from any peer, so a caller
     * that reaches the gateway without passing the edge could choose its own value. The
     * gateway is reachable only through Caddy from the internet (hosted-beta publishes no
     * gateway port); Caddy replaces the header for untrusted clients. The edge owns it.
     */
    @Test
    void underTheFrameworkStrategyTheEdgeMustOwnForwardedFor() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(CLIENT, 51234))
                .header("X-Forwarded-For", "203.0.113.250")
                .build();
        ServerHttpRequest transformed = new ForwardedHeaderTransformer().apply(request);

        CapturingChain chain = new CapturingChain();
        filter.filter(MockServerWebExchange.from(request).mutate().request(transformed).build(), chain).block();

        assertThat(forwarded(chain)).isEqualTo("203.0.113.250");
    }

    private CapturingChain run(MockServerHttpRequest request) {
        CapturingChain chain = new CapturingChain();
        filter.filter(MockServerWebExchange.from(request), chain).block();
        return chain;
    }

    private static String forwarded(CapturingChain chain) {
        return chain.captured.getRequest().getHeaders().getFirst(GatewayHeaders.CLIENT_IP);
    }

    private static final class CapturingChain implements GatewayFilterChain {
        private ServerWebExchange captured;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.captured = exchange;
            return Mono.empty();
        }
    }
}
