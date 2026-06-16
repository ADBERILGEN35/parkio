package com.parkio.gateway.infrastructure.config;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Resolves the real client IP for anonymous rate-limit keying in a proxy-aware,
 * spoofing-resistant way.
 *
 * <p><strong>Trust model.</strong> {@code X-Forwarded-For} is attacker-controllable and is
 * therefore <em>only</em> consulted when the request's immediate socket peer
 * ({@code remoteAddress}) is one of the configured {@code trustedProxies} (CIDRs / IPs).
 * If the peer is not trusted (e.g. a direct-to-gateway connection), the header is ignored
 * entirely and the socket peer is used — the same behaviour as having no proxy. An empty
 * trusted-proxy list means "trust nothing", which is the safe default for deployments that
 * are not behind a known proxy.
 *
 * <p><strong>Chain selection.</strong> When the peer is trusted, the {@code X-Forwarded-For}
 * chain is walked <em>right-to-left</em> and the first address that is <em>not</em> itself a
 * trusted proxy is selected (i.e. the real client as observed by the trusted proxy). This is
 * deliberately not "left-most": a client can inject a forged left-most value, but that value
 * always sits to the LEFT of the address the trusted proxy actually saw, so it is never
 * chosen. The same algorithm transparently handles multiple chained proxies.
 *
 * <p><strong>Safety.</strong> Only validated IP literals are ever returned, so a raw/oversized
 * header value can never leak into a Redis rate-limit key. Parsing never performs DNS
 * resolution. IPv4-mapped IPv6 addresses are normalised to their IPv4 form so the same client
 * maps to a single bucket regardless of the JVM's IP stack.
 */
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");

    /** Characters allowed in an IP literal — guards {@link InetAddress#getByName} against DNS. */
    private static final Pattern IP_LITERAL_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final List<CidrRange> trustedProxies;

    public ClientIpResolver(List<String> trustedProxyCidrs) {
        List<CidrRange> ranges = new ArrayList<>();
        if (trustedProxyCidrs != null) {
            for (String entry : trustedProxyCidrs) {
                CidrRange range = CidrRange.parse(entry);
                if (range != null) {
                    ranges.add(range);
                }
            }
        }
        this.trustedProxies = List.copyOf(ranges);
    }

    /**
     * @return the normalised client IP literal to key on, or {@code null} if it cannot be
     *     determined (the caller should fall back to a constant bucket — never fail open).
     */
    public String resolve(ServerHttpRequest request) {
        InetAddress remote = remoteAddress(request);
        if (remote != null && isTrusted(remote)) {
            String client = clientFromForwardedFor(request.getHeaders());
            if (client != null) {
                return client;
            }
        }
        return remote != null ? normalize(remote) : null;
    }

    private boolean isTrusted(InetAddress addr) {
        InetAddress comparable = unwrapV4Mapped(addr);
        for (CidrRange range : trustedProxies) {
            if (range.contains(comparable)) {
                return true;
            }
        }
        return false;
    }

    private String clientFromForwardedFor(HttpHeaders headers) {
        List<String> values = headers.get(X_FORWARDED_FOR);
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<InetAddress> chain = new ArrayList<>();
        for (String value : values) {
            for (String token : value.split(",")) {
                InetAddress ip = parseIpLiteral(token);
                if (ip != null) {
                    chain.add(unwrapV4Mapped(ip));
                }
            }
        }
        // Right-to-left: first non-trusted hop is the real client behind the trusted proxies.
        for (int i = chain.size() - 1; i >= 0; i--) {
            InetAddress candidate = chain.get(i);
            if (!isTrusted(candidate)) {
                return candidate.getHostAddress();
            }
        }
        return null;
    }

    private static InetAddress remoteAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote != null ? remote.getAddress() : null;
    }

    private static String normalize(InetAddress addr) {
        return unwrapV4Mapped(addr).getHostAddress();
    }

    /**
     * Parses an IP literal without DNS. Returns {@code null} for blank, malformed, or
     * hostname-like input. Tolerates surrounding whitespace, {@code [..]} brackets, and a
     * {@code %zone} suffix.
     */
    private static InetAddress parseIpLiteral(String raw) {
        if (raw == null) {
            return null;
        }
        String ip = raw.trim();
        if (ip.isEmpty()) {
            return null;
        }
        if (ip.startsWith("[") && ip.endsWith("]") && ip.length() > 2) {
            ip = ip.substring(1, ip.length() - 1);
        }
        int zone = ip.indexOf('%');
        if (zone >= 0) {
            ip = ip.substring(0, zone);
        }
        if (ip.isEmpty() || !IP_LITERAL_CHARS.matcher(ip).matches()) {
            return null;
        }
        boolean ipv6 = ip.indexOf(':') >= 0;
        if (!ipv6 && !IPV4.matcher(ip).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    /** Collapses an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) to its IPv4 form. */
    private static InetAddress unwrapV4Mapped(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        if (bytes.length == 16) {
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    return addr;
                }
            }
            if ((bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
                try {
                    return InetAddress.getByAddress(
                            new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
                } catch (UnknownHostException ex) {
                    return addr;
                }
            }
        }
        return addr;
    }

    /** An IPv4/IPv6 CIDR range used to match trusted proxies. */
    static final class CidrRange {

        private final byte[] network;
        private final int prefixBits;

        private CidrRange(byte[] network, int prefixBits) {
            this.network = network;
            this.prefixBits = prefixBits;
        }

        static CidrRange parse(String entry) {
            if (entry == null) {
                return null;
            }
            String value = entry.trim();
            if (value.isEmpty()) {
                return null;
            }
            String ipPart = value;
            Integer prefix = null;
            int slash = value.indexOf('/');
            if (slash >= 0) {
                ipPart = value.substring(0, slash);
                try {
                    prefix = Integer.parseInt(value.substring(slash + 1).trim());
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            InetAddress addr = parseIpLiteral(ipPart);
            if (addr == null) {
                return null;
            }
            byte[] network = unwrapV4Mapped(addr).getAddress();
            int maxBits = network.length * 8;
            int bits = (prefix == null) ? maxBits : prefix;
            if (bits < 0 || bits > maxBits) {
                return null;
            }
            return new CidrRange(network, bits);
        }

        boolean contains(InetAddress addr) {
            byte[] candidate = addr.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            int remainderBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            if (remainderBits > 0) {
                int mask = (0xFF << (8 - remainderBits)) & 0xFF;
                return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
            }
            return true;
        }
    }
}
