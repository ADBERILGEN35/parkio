package com.parkio.gateway.infrastructure.config;

import com.parkio.gateway.shared.GatewayHeaders;
import java.net.InetSocketAddress;
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
 * (anonymous endpoints such as login/register). This is registered as the default
 * key resolver so routes can reference it as {@code #{@userOrIpKeyResolver}}.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver userOrIpKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.USER_ID);
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            String ip = (remote != null && remote.getAddress() != null)
                    ? remote.getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("ip:" + ip);
        };
    }
}
