package com.parkio.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.infrastructure.security.PublicEndpoints;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the edge route table is complete — every backend service, including the
 * previously-unrouted analytics and ai-validation services, is reachable through the
 * gateway — and that those new routes are protected (not on the public allow-list).
 *
 * <p>Booting the full context here also exercises the rate-limit wiring: each route's
 * {@code RequestRateLimiter} filter resolves the {@code userOrIpKeyResolver} bean and
 * the auto-configured Redis rate limiter without error.
 */
@SpringBootTest
@ActiveProfiles("test")
class GatewayRoutesTest {

    private final PublicEndpoints publicEndpoints = new PublicEndpoints();

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void analyticsAndAiValidationRoutesAreRegistered() {
        List<String> routeIds =
                routeDefinitionLocator.getRouteDefinitions().map(RouteDefinition::getId).collectList().block();

        assertThat(routeIds)
                .contains("analytics-service", "ai-validation-service")
                // the geocoding route (parking-service behind a dedicated path + RL tier)
                .contains("geocoding-service")
                // and the previously-wired routes are still present
                .contains("auth-service", "user-service", "parking-service", "media-service",
                        "gamification-service", "notification-service", "moderation-service");
    }

    @Test
    void geocodingRouteIsProtected() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/geocoding/search").build();
        assertThat(publicEndpoints.isPublic(request)).isFalse();
    }

    @Test
    void analyticsRouteIsProtected() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/analytics/overview").build();
        assertThat(publicEndpoints.isPublic(request)).isFalse();
    }

    @Test
    void aiValidationRouteIsProtected() {
        MockServerHttpRequest manual = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/ai-validations/manual").build();
        MockServerHttpRequest lookup = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/ai-validations/media/abc").build();
        assertThat(publicEndpoints.isPublic(manual)).isFalse();
        assertThat(publicEndpoints.isPublic(lookup)).isFalse();
    }
}
