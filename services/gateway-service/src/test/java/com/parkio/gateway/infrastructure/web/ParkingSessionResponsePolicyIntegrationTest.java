package com.parkio.gateway.infrastructure.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.parkio.gateway.infrastructure.client.SessionEpochClient;
import com.parkio.gateway.infrastructure.client.UserStatusClient;
import com.parkio.gateway.infrastructure.client.UserStatusLookup;
import com.parkio.gateway.infrastructure.security.AuthenticatedUser;
import com.parkio.gateway.infrastructure.security.JwtTokenValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(properties = "parkio.gateway.public-surface.public-explore-enabled=true")
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class ParkingSessionResponsePolicyIntegrationTest {

    private static final String ACCESS_TOKEN = "parking-session-policy-token";
    private static final String USER_ID = "d431ad5a-f8ce-4be2-b4dc-248b47990b39";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private JwtTokenValidator tokenValidator;

    @MockitoBean
    private SessionEpochClient sessionEpochClient;

    @MockitoBean
    private UserStatusClient userStatusClient;

    @MockitoBean
    private RedisRateLimiter redisRateLimiter;

    @BeforeEach
    void configureAuthenticatedRateLimitedRequest() {
        when(tokenValidator.validate(ACCESS_TOKEN)).thenReturn(Mono.just(new AuthenticatedUser(
                USER_ID, "driver@parkio.test", List.of("USER"), "ACTIVE", 0L)));
        when(sessionEpochClient.fetchCurrentEpoch(USER_ID)).thenReturn(Mono.just(0L));
        when(userStatusClient.fetchStatus(USER_ID))
                .thenReturn(Mono.just(UserStatusLookup.found("ACTIVE")));
        when(redisRateLimiter.isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(false, Map.of())));
    }

    @Test
    void authenticationFailureCarriesNoStoreAtThePublicEdge() {
        webTestClient.get()
                .uri("/api/v1/parking/sessions/active")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("MISSING_TOKEN")
                .jsonPath("$.traceId").isNotEmpty()
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void rateLimitFailureCarriesNoStoreAndTheDocumentedApiError() {
        webTestClient.get()
                .uri("/api/v1/parking/sessions/active")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.message").isEqualTo("Too many requests. Try again later.")
                .jsonPath("$.traceId").isNotEmpty()
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void anonymousPublicExploreRateLimitFailureReturns429() {
        webTestClient.get()
                .uri("/api/v1/public/explore/facilities")
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void communityClaimAuthenticationFailureCarriesNoStoreAtThePublicEdge() {
        webTestClient.post()
                .uri("/api/v1/parking/spots/2b371445-8ab4-4a23-a1bd-9eb084187cf7/claim")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("MISSING_TOKEN")
                .jsonPath("$.traceId").isNotEmpty()
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void communityClaimRateLimitFailureCarriesNoStoreAndApiError() {
        webTestClient.post()
                .uri("/api/v1/parking/spots/2b371445-8ab4-4a23-a1bd-9eb084187cf7/claim")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.message").isEqualTo("Too many requests. Try again later.")
                .jsonPath("$.traceId").isNotEmpty()
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void unrelatedAuthenticationFailureIsNotGivenTheParkingSessionCachePolicy() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.CACHE_CONTROL);
    }
}
