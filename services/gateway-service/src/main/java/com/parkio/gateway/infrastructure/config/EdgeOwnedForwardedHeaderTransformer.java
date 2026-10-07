package com.parkio.gateway.infrastructure.config;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

/**
 * The framework forwarded-header transformer, minus the RFC 7239 {@code Forwarded} header
 * (CL-F15, PR #306 review B1).
 *
 * <p>Spring's {@link ForwardedHeaderTransformer} prefers {@code Forwarded: for=...} over
 * {@code X-Forwarded-For} when it rewrites the peer address. The edge (Caddy) replaces the
 * {@code X-Forwarded-*} headers for every untrusted client but passes {@code Forwarded} through, so
 * without this a client could choose the address the gateway keys its per-client limits on. This
 * transformer drops any {@code Forwarded} header before delegating; the peer then comes only from the
 * edge-owned {@code X-Forwarded-For}.
 */
public class EdgeOwnedForwardedHeaderTransformer extends ForwardedHeaderTransformer {

    static final String FORWARDED = "Forwarded";

    @Override
    public ServerHttpRequest apply(ServerHttpRequest request) {
        ServerHttpRequest edgeOwned = request.getHeaders().containsKey(FORWARDED)
                ? request.mutate().headers(headers -> headers.remove(FORWARDED)).build()
                : request;
        return super.apply(edgeOwned);
    }
}
