package com.parkio.gateway.infrastructure.config;

import com.parkio.gateway.shared.GatewayHeaders;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Wiring for the edge rate limiter. Spring Cloud Gateway's {@code RequestRateLimiter}
 * filter uses a Redis token bucket ({@code RedisRateLimiter}, auto-configured from the
 * reactive Redis starter) keyed by this {@link KeyResolver}. Per-route limits
 * (replenish/burst) are configured in {@code application.yml} and are env-overridable.
 *
 * <p>The bucket key is the <strong>authenticated user id</strong> when present
 * (the gateway-injected, trusted {@code X-User-Id}), so a logged-in caller is limited
 * as an individual regardless of source IP; otherwise it falls back to the client IP
 * (anonymous endpoints such as login/register). The anonymous client IP is derived in a
 * proxy-aware, spoofing-resistant way by {@link ClientIpResolver} (it consults
 * {@code X-Forwarded-For} only when the request comes from a configured trusted proxy).
 * This is registered as the default key resolver so routes can reference it as
 * {@code #{@userOrIpKeyResolver}}.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public ClientIpResolver clientIpResolver(TrustedProxyProperties properties) {
        return new ClientIpResolver(properties.getTrustedProxies());
    }

    @Bean
    public KeyResolver userOrIpKeyResolver(ClientIpResolver clientIpResolver) {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.USER_ID);
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            String clientIp = clientIpResolver.resolve(exchange.getRequest());
            return Mono.just("ip:" + (clientIp != null ? clientIp : "unknown"));
        };
    }
}
