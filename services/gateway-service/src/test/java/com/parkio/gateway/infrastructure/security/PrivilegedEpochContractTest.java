package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.admin.AdminApplicationService;
import com.parkio.auth.application.port.AdminAuditEventRepository;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.port.RoleRepository;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.metrics.AdminMetrics;
import com.parkio.auth.infrastructure.security.JwtService;
import com.parkio.auth.infrastructure.security.RsaKeyProvider;
import com.parkio.gateway.infrastructure.client.SessionEpochCache;
import com.parkio.gateway.infrastructure.client.SessionEpochClient;
import com.parkio.gateway.infrastructure.client.SessionEpochProperties;
import com.parkio.gateway.infrastructure.client.UserStatusCache;
import com.parkio.gateway.infrastructure.client.UserStatusClient;
import com.parkio.gateway.infrastructure.client.UserStatusLookup;
import com.parkio.gateway.infrastructure.client.UserStatusProperties;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Signed-token proof for both routed admin APIs and gateway-local waitlist admin/export. */
class PrivilegedEpochContractTest {

    private static final String ADMIN_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ID = "22222222-2222-2222-2222-222222222222";
    private final MutableClock cacheClock = new MutableClock();
    private final Map<String, Long> currentEpoch = new ConcurrentHashMap<>();
    private AuthenticationGlobalFilter authentication;
    private AuthorizationGlobalFilter authorization;
    private SessionEpochGlobalFilter routedEpoch;
    private WaitlistAdminSecurityWebFilter waitlistSecurity;
    private JwtService tokenIssuer;

    @BeforeEach
    void setUp() {
        com.parkio.auth.infrastructure.security.JwtProperties authJwt =
                new com.parkio.auth.infrastructure.security.JwtProperties();
        authJwt.setIssuer("parkio-test");
        authJwt.setAudience("parkio-test-api");
        authJwt.setAccessTokenTtl(Duration.ofMinutes(15));
        authJwt.setGenerateEphemeralKey(true);
        RsaKeyProvider keys = new RsaKeyProvider(authJwt);
        tokenIssuer = new JwtService(authJwt, keys, Clock.systemUTC(), new ObjectMapper());
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("parkio-test");
        jwtProperties.setAudience("parkio-test-api");
        JwtTokenValidator validator = new JwtTokenValidator(jwtProperties,
                keyId -> Mono.just(keys.publicKey()), new ObjectMapper());
        GatewayErrorResponseWriter errors = new GatewayErrorResponseWriter(new ObjectMapper(), cacheClock);
        SessionEpochClient client = mock(SessionEpochClient.class);
        when(client.fetchCurrentEpoch(anyString())).thenAnswer(inv ->
                Mono.just(currentEpoch.get(inv.getArgument(0))));
        SessionEpochVerifier verifier = new SessionEpochVerifier(client,
                new SessionEpochCache(cacheClock, new SessionEpochProperties()), errors);
        UserStatusClient statuses = mock(UserStatusClient.class);
        when(statuses.fetchStatus(anyString())).thenReturn(Mono.just(UserStatusLookup.found("ACTIVE")));
        AccountStatusVerifier accountStatus = new AccountStatusVerifier(statuses,
                new UserStatusCache(cacheClock, new UserStatusProperties()), errors);
        PublicEndpoints publicEndpoints = new PublicEndpoints(new com.parkio.gateway.infrastructure.config.GatewayPublicSurfaceProperties());
        authentication = new AuthenticationGlobalFilter(publicEndpoints, validator, errors);
        authorization = new AuthorizationGlobalFilter(new RouteAuthorizationRules(), errors);
        routedEpoch = new SessionEpochGlobalFilter(publicEndpoints, verifier);
        waitlistSecurity = new WaitlistAdminSecurityWebFilter(validator, verifier, accountStatus, errors);
        currentEpoch.put(ADMIN_ID, 0L);
        currentEpoch.put(OTHER_ID, 0L);
    }

    @Test
    void signedOldAdminTokenStopsAtEveryPrivilegedBoundaryAfterCacheExpiry() {
        AuthUser adminAccount = user(ADMIN_ID, List.of("USER", "ADMIN"), 0);
        String old = tokenIssuer.issue(adminAccount).token();
        String other = token(OTHER_ID, List.of("ADMIN"), 0);
        assertThat(routed(HttpMethod.GET, "/api/v1/admin/users", old)).isNull();
        assertThat(local("/api/v1/waitlist/export", old)).isNull();
        AuthUserRepository users = mock(AuthUserRepository.class);
        when(users.findByIdForUpdate(adminAccount.id())).thenReturn(Optional.of(adminAccount));
        when(users.save(adminAccount)).thenReturn(adminAccount);
        AdminApplicationService admin = new AdminApplicationService(users,
                mock(RefreshTokenRepository.class), mock(RoleRepository.class),
                mock(AdminAuditEventRepository.class), mock(OutboxEventAppender.class),
                mock(InboxEventRepository.class), mock(AuthApplicationService.class),
                mock(AdminMetrics.class), Clock.systemUTC());
        admin.revokeRole(UUID.randomUUID(), Set.of("SUPER_ADMIN"), adminAccount.id(), RoleName.ADMIN,
                "synthetic demotion");
        assertThat(adminAccount.hasRole(RoleName.ADMIN)).isFalse();
        assertThat(adminAccount.sessionEpoch()).isEqualTo(1L);
        currentEpoch.put(ADMIN_ID, adminAccount.sessionEpoch());

        // A previously cached epoch can be used for at most the configured 30 seconds.
        cacheClock.advance(Duration.ofSeconds(29));
        assertThat(routed(HttpMethod.GET, "/api/v1/admin/users", old)).isNull();
        cacheClock.advance(Duration.ofSeconds(1));
        assertThat(routed(HttpMethod.GET, "/api/v1/admin/users", old)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(routed(HttpMethod.POST,
                "/api/v1/admin/users/33333333-3333-3333-3333-333333333333/roles", old))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(local("/api/v1/waitlist/export", old)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(local("/api/v1/waitlist/admin", old)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(routed(HttpMethod.GET, "/api/v1/admin/users", other)).isNull();

        String current = tokenIssuer.issue(adminAccount).token();
        assertThat(routed(HttpMethod.GET, "/api/v1/admin/users", current)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(local("/api/v1/waitlist/export", current)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void missingAndInvalidJwtNeverReachAdminRoutes() {
        assertThat(routed(HttpMethod.GET, "/api/v1/admin/users", null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(routed(HttpMethod.GET, "/api/v1/admin/users", "invalid.jwt.token"))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(local("/api/v1/waitlist/export", null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(local("/api/v1/waitlist/export", "invalid.jwt.token"))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpStatus routed(HttpMethod method, String path, String token) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(method, path);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        GatewayFilterChain forwarded = ex -> {
            ex.getAttributes().put("reachedRoute", true);
            return Mono.empty();
        };
        authentication.filter(exchange, ex -> authorization.filter(ex,
                authorized -> routedEpoch.filter(authorized, forwarded))).block();
        if (exchange.getResponse().getStatusCode() == null) {
            assertThat(Boolean.TRUE.equals(exchange.getAttribute("reachedRoute"))).isTrue();
        } else {
            assertThat(Boolean.TRUE.equals(exchange.getAttribute("reachedRoute"))).isFalse();
        }
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }

    private HttpStatus local(String path, String token) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        WebFilterChain forwarded = ex -> {
            ex.getAttributes().put("reachedRoute", true);
            return Mono.empty();
        };
        waitlistSecurity.filter(exchange, forwarded).block();
        if (exchange.getResponse().getStatusCode() == null) {
            assertThat(Boolean.TRUE.equals(exchange.getAttribute("reachedRoute"))).isTrue();
        } else {
            assertThat(Boolean.TRUE.equals(exchange.getAttribute("reachedRoute"))).isFalse();
        }
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }

    private String token(String userId, List<String> roles, long epoch) {
        return tokenIssuer.issue(user(userId, roles, epoch)).token();
    }

    private static AuthUser user(String userId, List<String> roles, long epoch) {
        Instant now = Instant.now();
        Set<Role> granted = roles.stream()
                .map(name -> new Role(UUID.randomUUID(), RoleName.valueOf(name)))
                .collect(java.util.stream.Collectors.toSet());
        return new AuthUser(UUID.fromString(userId), "synthetic@parkio.example",
                "synthetic-hash", AuthUserStatus.ACTIVE, null, true, now,
                null, null, null, com.parkio.auth.domain.EmailLocale.TR,
                epoch, granted, now, null);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-28T00:00:00Z");

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
