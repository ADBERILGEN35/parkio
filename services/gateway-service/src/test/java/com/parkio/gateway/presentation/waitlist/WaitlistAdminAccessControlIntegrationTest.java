package com.parkio.gateway.presentation.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.gateway.application.waitlist.WaitlistAdminCounts;
import com.parkio.gateway.application.waitlist.WaitlistAdminPage;
import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistInterestRepository;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import com.parkio.gateway.infrastructure.client.SessionEpochClient;
import com.parkio.gateway.infrastructure.client.SessionEpochUnavailableException;
import com.parkio.gateway.infrastructure.client.UserStatusClient;
import com.parkio.gateway.infrastructure.client.UserStatusLookup;
import com.parkio.gateway.infrastructure.client.UserStatusUnavailableException;
import com.parkio.gateway.infrastructure.security.AuthenticatedUser;
import com.parkio.gateway.infrastructure.security.JwtTokenValidator;
import com.parkio.gateway.infrastructure.security.WaitlistAdminSecurityWebFilter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.RequestPath;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import reactor.core.publisher.Mono;

/**
 * Audit F-01 / F-13 regression: the waitlist admin surface through the real WebFilter
 * chain, handler mapping and controller (full context), with the repository and the
 * identity/status/epoch lookups mocked. Every rejected request is checked for zero admin
 * repository calls, not only its status.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class WaitlistAdminAccessControlIntegrationTest {

    private static final List<String> ADMIN_PATHS = List.of(
            "/api/v1/waitlist/admin",
            "/api/v1/waitlist/admin/summary",
            "/api/v1/waitlist/export");
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    // CORS needs an absolute request URI: with a relative one the same-origin check
    // cannot parse the request's own origin and rejects every Origin as malformed.
    private static final String GATEWAY_BASE = "http://gateway.parkio.test";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @MockBean
    private WaitlistInterestRepository repository;

    @MockBean
    private JwtTokenValidator tokenValidator;

    @MockBean
    private SessionEpochClient sessionEpochClient;

    @MockBean
    private UserStatusClient userStatusClient;

    @MockBean
    private WaitlistRateLimiter rateLimiter;

    @MockBean
    private WaitlistEmailSender emailSender;

    @BeforeEach
    void setUp() {
        when(repository.exportConfirmed(any(), any())).thenReturn(List.of());
        when(repository.countByStatus()).thenReturn(new WaitlistAdminCounts(0, 0, 0, 0));
        when(repository.findAdminPage(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new WaitlistAdminPage(List.of(), 0, 20, 0, 0));
        when(tokenValidator.validate("expired-token"))
                .thenReturn(Mono.error(new IllegalArgumentException("expired")));
        when(tokenValidator.validate("empty-token")).thenReturn(Mono.empty());
        when(sessionEpochClient.fetchCurrentEpoch(anyString())).thenReturn(Mono.just(0L));
        when(userStatusClient.fetchStatus(anyString())).thenReturn(Mono.just(UserStatusLookup.found("ACTIVE")));
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
    }

    @Test
    void anonymousRequestsOnEveryMethodAndPathAreDeniedWithoutRepositoryCalls() {
        List<String> paths = List.of(
                "/api/v1/waitlist/admin", "/api/v1/waitlist/admin/",
                "/api/v1/waitlist/admin/summary", "/api/v1/waitlist/admin/summary/",
                "/api/v1/waitlist/export", "/api/v1/waitlist/export/");
        List<HttpMethod> methods = List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT,
                HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS);
        for (String path : paths) {
            for (HttpMethod method : methods) {
                exchange(method, path, null).expectStatus().isUnauthorized();
            }
        }
        assertNoAdminRepositoryCalls();
        verify(tokenValidator, never()).validate(anyString());
    }

    @Test
    void anonymousHeadExportNoLongerReturnsCsv() {
        EntityExchangeResult<byte[]> result = webTestClient.method(HttpMethod.HEAD).uri("/api/v1/waitlist/export")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().returnResult();
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(result.getResponseHeaders().getContentType()).isNotEqualTo(MediaType.parseMediaType("text/csv"));
        assertNoAdminRepositoryCalls();
    }

    @Test
    void invalidExpiredOrEmptyTokenIsDenied() {
        for (String path : ADMIN_PATHS) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
                exchange(method, path, "expired-token").expectStatus().isUnauthorized();
                exchange(method, path, "empty-token").expectStatus().isUnauthorized();
            }
            webTestClient.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46YWRtaW4=")
                    .exchange().expectStatus().isUnauthorized();
        }
        assertNoAdminRepositoryCalls();
    }

    @Test
    void authenticatedNonAdminIsDeniedBeforeEpochOrStatusLookup() {
        for (String role : List.of("USER", "MODERATOR")) {
            String token = token(role, 0L);
            for (String path : ADMIN_PATHS) {
                for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST)) {
                    exchange(method, path, token).expectStatus().isForbidden();
                }
            }
        }
        assertNoAdminRepositoryCalls();
        verify(sessionEpochClient, never()).fetchCurrentEpoch(anyString());
        verify(userStatusClient, never()).fetchStatus(anyString());
    }

    @Test
    void spoofedIdentityHeadersDoNotGrantAccess() {
        webTestClient.get().uri("/api/v1/waitlist/export")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Roles", "ADMIN,SUPER_ADMIN")
                .exchange()
                .expectStatus().isUnauthorized();
        assertNoAdminRepositoryCalls();
    }

    @Test
    void adminAndSuperAdminKeepAccess() {
        for (String role : List.of("ADMIN", "SUPER_ADMIN")) {
            String token = token(role, 0L);
            webTestClient.get().uri("/api/v1/waitlist/admin/summary")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.total").isEqualTo(0);
            webTestClient.get().uri("/api/v1/waitlist/admin?status=CONFIRMED")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.totalElements").isEqualTo(0);
            webTestClient.get().uri("/api/v1/waitlist/export")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith("text/csv")
                    .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
            // HEAD stays available to an authorized admin (WebFlux serves it from @GetMapping).
            webTestClient.method(HttpMethod.HEAD).uri("/api/v1/waitlist/export")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk();
        }
    }

    @Test
    void unsupportedMethodFromAdminIsRefusedByControllerWithoutRepositoryCalls() {
        String token = token("ADMIN", 0L);
        for (String path : ADMIN_PATHS) {
            for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                exchange(method, path, token).expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            }
        }
        assertNoAdminRepositoryCalls();
    }

    @Test
    void suspendedBannedDeletedOrUnknownAccountsAreDenied() {
        for (String status : List.of("SUSPENDED", "BANNED", "DELETED", "PENDING_DELETION")) {
            String userId = UUID.randomUUID().toString();
            String token = token(userId, "ADMIN", 0L);
            when(userStatusClient.fetchStatus(userId)).thenReturn(Mono.just(UserStatusLookup.found(status)));
            for (String path : ADMIN_PATHS) {
                exchange(HttpMethod.GET, path, token).expectStatus().isForbidden()
                        .expectBody().jsonPath("$.code").isEqualTo("ACCOUNT_NOT_ACTIVE");
                exchange(HttpMethod.HEAD, path, token).expectStatus().isForbidden();
            }
        }
        String unknownId = UUID.randomUUID().toString();
        when(userStatusClient.fetchStatus(unknownId)).thenReturn(Mono.just(UserStatusLookup.notFound()));
        exchange(HttpMethod.GET, "/api/v1/waitlist/export", token(unknownId, "ADMIN", 0L))
                .expectStatus().isForbidden();
        assertNoAdminRepositoryCalls();
    }

    @Test
    void staleSessionEpochIsDeniedAndLegacyTokenFailsAfterBump() {
        String userId = UUID.randomUUID().toString();
        when(sessionEpochClient.fetchCurrentEpoch(userId)).thenReturn(Mono.just(4L));
        for (String path : ADMIN_PATHS) {
            exchange(HttpMethod.GET, path, token(userId, "SUPER_ADMIN", 3L)).expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.code").isEqualTo("TOKEN_REVOKED");
            exchange(HttpMethod.HEAD, path, token(userId, "SUPER_ADMIN", 3L)).expectStatus().isUnauthorized();
            exchange(HttpMethod.GET, path, token(userId, "SUPER_ADMIN", null)).expectStatus().isUnauthorized();
        }
        assertNoAdminRepositoryCalls();
        // Revocation is decided before the account-status lookup, as on routed traffic.
        verify(userStatusClient, never()).fetchStatus(userId);

        exchange(HttpMethod.GET, "/api/v1/waitlist/admin/summary", token(userId, "SUPER_ADMIN", 4L))
                .expectStatus().isOk();
    }

    @Test
    void epochOrStatusLookupFailureFailsClosed() {
        String epochDown = UUID.randomUUID().toString();
        when(sessionEpochClient.fetchCurrentEpoch(epochDown))
                .thenReturn(Mono.error(new SessionEpochUnavailableException("down")));
        String statusDown = UUID.randomUUID().toString();
        when(userStatusClient.fetchStatus(statusDown))
                .thenReturn(Mono.error(new UserStatusUnavailableException("down")));
        for (String path : ADMIN_PATHS) {
            exchange(HttpMethod.GET, path, token(epochDown, "ADMIN", 0L))
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectBody().jsonPath("$.code").isEqualTo("SESSION_EPOCH_UNAVAILABLE");
            exchange(HttpMethod.GET, path, token(statusDown, "ADMIN", 0L))
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectBody().jsonPath("$.code").isEqualTo("USER_STATUS_UNAVAILABLE");
            exchange(HttpMethod.HEAD, path, token(epochDown, "ADMIN", 0L))
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            exchange(HttpMethod.HEAD, path, token(statusDown, "ADMIN", 0L))
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
        assertNoAdminRepositoryCalls();
    }

    @Test
    void corsPreflightIsAnsweredWithoutAuthenticationOrHandlerExecution() {
        for (String path : ADMIN_PATHS) {
            webTestClient.options().uri(GATEWAY_BASE + path)
                    .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            webTestClient.options().uri(GATEWAY_BASE + path)
                    .header(HttpHeaders.ORIGIN, "https://attacker.invalid")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        }
        assertNoAdminRepositoryCalls();
        verify(tokenValidator, never()).validate(anyString());
    }

    @Test
    void credentialedCrossOriginAdminRequestStillCarriesCorsHeaders() {
        webTestClient.get().uri(GATEWAY_BASE + "/api/v1/waitlist/admin/summary")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("ADMIN", 0L))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN);
    }

    @Test
    void publicWaitlistFlowsStayAnonymous() {
        webTestClient.post().uri("/api/v1/waitlist/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"unknown-token\"}")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotIn(401, 403));
        webTestClient.post().uri("/api/v1/waitlist/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"unknown-token\"}")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotIn(401, 403));
        webTestClient.post().uri("/api/v1/waitlist/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"someone@parkio.test\"}")
                .exchange()
                .expectStatus().isAccepted();
        verify(repository).confirmByTokenHash(anyString(), any());
        verify(repository).withdrawByTokenHash(anyString(), any());
        verify(tokenValidator, never()).validate(anyString());
    }

    @Test
    void everyNonPublicWaitlistControllerMappingIsProtectedByPath() {
        Set<String> publicPosts = Set.of(
                "/api/v1/waitlist", "/api/v1/waitlist/confirm",
                "/api/v1/waitlist/withdraw", "/api/v1/waitlist/resend");
        int checked = 0;
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!WaitlistController.class.equals(handler.getBeanType())) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (var pattern : info.getPatternsCondition().getPatterns()) {
                String path = pattern.getPatternString();
                checked++;
                if (publicPosts.contains(path) && methods.equals(Set.of(RequestMethod.POST))) {
                    continue;
                }
                assertThat(isProtected(path))
                        .as("%s %s must be covered by WaitlistAdminSecurityWebFilter", methods, path)
                        .isTrue();
            }
        }
        assertThat(checked).isEqualTo(7);
    }

    private static boolean isProtected(String path) {
        return WaitlistAdminSecurityWebFilter.isProtected(RequestPath.parse(path, null).pathWithinApplication());
    }

    private WebTestClient.ResponseSpec exchange(HttpMethod method, String path, String token) {
        WebTestClient.RequestBodySpec spec = webTestClient.method(method).uri(path);
        if (token != null) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return spec.exchange();
    }

    private String token(String role, Long sessionEpoch) {
        return token(UUID.randomUUID().toString(), role, sessionEpoch);
    }

    private String token(String userId, String role, Long sessionEpoch) {
        String token = "tok-" + UUID.randomUUID();
        when(tokenValidator.validate(token)).thenReturn(Mono.just(new AuthenticatedUser(
                userId, role.toLowerCase() + "@parkio.test", List.of(role), "ACTIVE", sessionEpoch)));
        return token;
    }

    private void assertNoAdminRepositoryCalls() {
        verify(repository, never()).exportConfirmed(any(), any());
        verify(repository, never()).countByStatus();
        verify(repository, never()).findAdminPage(any(), any(), any(), anyInt(), anyInt());
        clearInvocations(repository);
    }
}
