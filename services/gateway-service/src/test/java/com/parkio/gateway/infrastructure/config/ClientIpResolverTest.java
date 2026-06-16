package com.parkio.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

/**
 * Proxy-aware, spoofing-resistant client-IP resolution for anonymous rate limiting.
 *
 * <p>Trust model: {@code X-Forwarded-For} is consulted ONLY when the socket peer is a
 * configured trusted proxy, and the real client is the right-most non-proxy hop (so a
 * client-injected left-most value can never win). Anything malformed or untrusted falls
 * back to the socket peer — never fails open, never leaks a raw header into the key.
 */
class ClientIpResolverTest {

    private static final List<String> DOCKER_PROXIES =
            List.of("172.16.0.0/12", "10.0.0.0/8", "127.0.0.1/32");

    private final ClientIpResolver resolver = new ClientIpResolver(DOCKER_PROXIES);

    @Test
    void trustedProxyUsesRealClientFromForwardedFor() {
        String ip = resolver.resolve(request("172.18.0.5", "203.0.113.9"));
        assertThat(ip).isEqualTo("203.0.113.9");
    }

    @Test
    void untrustedRemoteIgnoresSpoofedForwardedFor() {
        // Direct connection (not via a trusted proxy) — the header is attacker-controlled.
        String ip = resolver.resolve(request("203.0.113.50", "10.1.2.3"));
        assertThat(ip).isEqualTo("203.0.113.50");
    }

    @Test
    void malformedForwardedForFallsBackToRemote() {
        String ip = resolver.resolve(request("172.18.0.5", "not-an-ip, also.bad.value"));
        assertThat(ip).isEqualTo("172.18.0.5");
    }

    @Test
    void multipleForwardedValuesSkipTrailingTrustedProxy() {
        // client -> edge-proxy(203..9 appended) -> internal-proxy(172.18.0.4) -> gateway
        String ip = resolver.resolve(request("172.18.0.5", "203.0.113.9, 172.18.0.4"));
        assertThat(ip).isEqualTo("203.0.113.9");
    }

    @Test
    void clientInjectedLeftMostValueIsNotTrusted() {
        // Attacker sets XFF: "1.2.3.4"; the trusted proxy APPENDS the real peer 203.0.113.9.
        // Right-to-left selection picks the proxy-observed client, never the forged left value.
        String ip = resolver.resolve(request("172.18.0.5", "1.2.3.4, 203.0.113.9"));
        assertThat(ip).isEqualTo("203.0.113.9");
    }

    @Test
    void forwardedForAcrossMultipleHeaderLinesIsParsed() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/auth/login")
                .remoteAddress(socket("172.18.0.5"))
                .header("X-Forwarded-For", "203.0.113.9")
                .header("X-Forwarded-For", "172.18.0.4")
                .build();

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void noTrustedProxiesConfiguredIgnoresForwardedFor() {
        ClientIpResolver noTrust = new ClientIpResolver(List.of());
        // Even from a private/Docker peer, with no trust configured the header is ignored.
        assertThat(noTrust.resolve(request("172.18.0.5", "203.0.113.9"))).isEqualTo("172.18.0.5");
    }

    @Test
    void normalizesIpv4MappedForwardedValue() {
        String ip = resolver.resolve(request("172.18.0.5", "::ffff:203.0.113.9"));
        assertThat(ip).isEqualTo("203.0.113.9");
    }

    @Test
    void normalizesIpv6ClientToCanonicalForm() throws UnknownHostException {
        String ip = resolver.resolve(request("172.18.0.5", "2001:db8::1"));
        assertThat(ip).isEqualTo(InetAddress.getByName("2001:db8::1").getHostAddress());
    }

    @Test
    void emptyForwardedForFallsBackToRemote() {
        String ip = resolver.resolve(request("172.18.0.5", "   "));
        assertThat(ip).isEqualTo("172.18.0.5");
    }

    @Test
    void allTrustedChainFallsBackToRemote() {
        // Every forwarded hop is itself a trusted proxy -> no client to extract.
        String ip = resolver.resolve(request("172.18.0.5", "10.0.0.7, 172.18.0.4"));
        assertThat(ip).isEqualTo("172.18.0.5");
    }

    private static MockServerHttpRequest request(String remoteIp, String forwardedFor) {
        return MockServerHttpRequest
                .post("/api/v1/auth/login")
                .remoteAddress(socket(remoteIp))
                .header("X-Forwarded-For", forwardedFor)
                .build();
    }

    private static InetSocketAddress socket(String ip) {
        return new InetSocketAddress(ip, 4444);
    }
}
