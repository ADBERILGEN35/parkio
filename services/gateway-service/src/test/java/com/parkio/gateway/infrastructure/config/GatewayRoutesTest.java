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

    private final PublicEndpoints publicEndpoints = new PublicEndpoints(new GatewayPublicSurfaceProperties());

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
                .contains("account-erasure")
                .contains("public-explore")
                // and the previously-wired routes are still present
                .contains("auth-service", "user-service", "places-service", "parking-service", "media-service",
                        "gamification-service", "notification-service", "moderation-service");
    }

    @Test
    void publicExploreRouteIsExactGetOnlyAndRateLimitedAtOnePerSecondBurstFive() {
        RouteDefinition route = routeDefinitionLocator.getRouteDefinitions()
                .filter(candidate -> "public-explore".equals(candidate.getId()))
                .blockFirst();

        assertThat(route).isNotNull();
        assertThat(route.getPredicates().stream().filter(predicate -> "Method".equals(predicate.getName())))
                .singleElement()
                .satisfies(predicate -> assertThat(predicate.getArgs()).containsValue("GET"));
        assertThat(route.getPredicates().stream().filter(predicate -> "Path".equals(predicate.getName())))
                .singleElement()
                .satisfies(predicate -> assertThat(predicate.getArgs().values())
                        .contains("/api/v1/public/explore/facilities")
                        .contains("/api/v1/public/explore/facilities/{facilityId}"));
        assertThat(route.getFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.getName()).isEqualTo("RequestRateLimiter");
            assertThat(filter.getArgs()).containsEntry("redis-rate-limiter.replenishrate", "1")
                    .containsEntry("redis-rate-limiter.burstcapacity", "5")
                    .containsValue("#{@publicExploreIpKeyResolver}");
        });
    }

    @Test
    void publicExploreAnonymousRulesAreFlaggedAndGetOnly() {
        GatewayPublicSurfaceProperties enabled = new GatewayPublicSurfaceProperties();
        enabled.setPublicExploreEnabled(true);
        PublicEndpoints endpoints = new PublicEndpoints(enabled);

        assertThat(endpoints.isPublic(MockServerHttpRequest.get("/api/v1/public/explore/facilities").build()))
                .isTrue();
        assertThat(endpoints.isPublic(MockServerHttpRequest.get(
                "/api/v1/public/explore/facilities/00000000-0000-0000-0000-000000000001").build()))
                .isTrue();
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            assertThat(endpoints.isPublic(MockServerHttpRequest
                    .method(method, "/api/v1/public/explore/facilities").build())).isFalse();
        }
        assertThat(endpoints.isPublic(MockServerHttpRequest
                .get("/api/v1/public/explore/facilities/not-a-uuid/extra").build())).isFalse();
        assertThat(publicEndpoints.isPublic(MockServerHttpRequest
                .get("/api/v1/public/explore/facilities").build())).isFalse();
        assertThat(publicEndpoints.isPublic(MockServerHttpRequest
                .get("/api/v1/parking/facilities/nearby").build())).isFalse();
    }

    @Test
    void registrationModeBootstrapIsPublicReadOnly() {
        assertThat(publicEndpoints.isPublic(MockServerHttpRequest
                .get("/api/v1/auth/registration-mode").build())).isTrue();
        assertThat(publicEndpoints.isPublic(MockServerHttpRequest
                .post("/api/v1/auth/registration-mode").build())).isFalse();
    }

    @Test
    void existingPrivateProductAndOperatorSurfacesRemainAuthenticated() {
        for (var request : List.of(
                MockServerHttpRequest.get("/api/v1/parking/spots").build(),
                MockServerHttpRequest.get("/api/v1/parking/facilities/nearby").build(),
                MockServerHttpRequest.get("/api/v1/places/favourites/parking").build(),
                MockServerHttpRequest.get("/api/v1/users/me").build(),
                MockServerHttpRequest.get("/api/v1/parking/sessions/current").build(),
                MockServerHttpRequest.post("/api/v1/parking/recommendations").build(),
                MockServerHttpRequest.get("/api/v1/admin/dashboard").build(),
                MockServerHttpRequest.post("/api/v1/parking/municipal/sync").build(),
                MockServerHttpRequest.post("/api/v1/admin/invites").build())) {
            assertThat(publicEndpoints.isPublic(request)).isFalse();
        }
    }

    @Test
    void placesRouteIsProtected() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/places/saved").build();
        assertThat(publicEndpoints.isPublic(request)).isFalse();
    }

    @Test
    void favouritesRouteIsProtected() {
        MockServerHttpRequest parking = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/places/favourites/parking").build();
        MockServerHttpRequest destinations = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/places/favourites/destinations").build();
        assertThat(publicEndpoints.isPublic(parking)).isFalse();
        assertThat(publicEndpoints.isPublic(destinations)).isFalse();
    }

    @Test
    void recentsRouteIsProtected() {
        MockServerHttpRequest destinations = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/places/recents/destinations").build();
        MockServerHttpRequest parking = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/places/recents/parking").build();
        assertThat(publicEndpoints.isPublic(destinations)).isFalse();
        assertThat(publicEndpoints.isPublic(parking)).isFalse();
    }

    @Test
    void recommendationsRouteIsProtected() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/parking/recommendations").build();
        assertThat(publicEndpoints.isPublic(request)).isFalse();
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

    @Test
    void accountErasureRouteIsProtected() {
        MockServerHttpRequest delete = MockServerHttpRequest
                .method(HttpMethod.DELETE, "/api/v1/account").build();
        MockServerHttpRequest status = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/account/deletion-status").build();
        assertThat(publicEndpoints.isPublic(delete)).isFalse();
        assertThat(publicEndpoints.isPublic(status)).isFalse();
    }
}
