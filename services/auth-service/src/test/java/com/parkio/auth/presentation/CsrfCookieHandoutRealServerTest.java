package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The web login's double-submit contract on a real servlet container: the login response sets the
 * XSRF-TOKEN cookie to the token it returns in the body, next to the two refresh cookies (#515).
 *
 * <p>MockMvc cannot show this. Spring Framework 7 copies ResponseEntity headers onto the servlet response
 * with setHeader, which removes every Set-Cookie header already written; MockMvc's cookie list kept the
 * dropped cookie, so only the raw headers of a real server reveal it. Before the fix the login sent no
 * XSRF-TOKEN cookie, and the first web cookie refresh after login was refused (403 CSRF_TOKEN_REQUIRED).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsrfCookieHandoutRealServerTest {

    private static final String GATEWAY_SECRET = "test-only-parkio-gateway-internal-secret-0123456789";
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "StrongerPass123";

    @LocalServerPort
    private int port;

    @Autowired
    private AuthApplicationService authService;

    @Autowired
    private RoleJpaRepository roles;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LoginFailureTracker loginFailureTracker;

    @MockitoBean
    private EmailVerificationSender emailVerificationSender;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("parkio.security.refresh-cookie.allowed-origins", () -> ALLOWED_ORIGIN);
        registry.add("parkio.security.refresh-cookie.secure", () -> "true");
        registry.add("parkio.security.refresh-cookie.same-site", () -> "Strict");
        registry.add("parkio.gateway.internal-secret", () -> GATEWAY_SECRET);
    }

    @Test
    void webLoginSetsTheXsrfCookieToTheTokenItReturns() throws Exception {
        if (roles.findByName(RoleName.USER).isEmpty()) {
            roles.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
        String email = "csrf-real-server-" + UUID.randomUUID() + "@example.com";
        clearInvocations(emailVerificationSender);
        authService.register(new RegisterCommand(email, PASSWORD));
        ArgumentCaptor<String> verification = ArgumentCaptor.forClass(String.class);
        verify(emailVerificationSender, atLeastOnce()).sendVerificationLink(eq(email), verification.capture(), any());
        authService.verifyEmail(new VerifyEmailCommand(verification.getValue()));

        HttpResponse<String> login = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(login.statusCode()).isEqualTo(200);
        String token = objectMapper.readTree(login.body()).get("csrfToken").asText();
        List<String> cookies = login.headers().allValues("Set-Cookie");
        assertThat(cookies)
                .anySatisfy(cookie -> assertThat(cookie).startsWith("XSRF-TOKEN=" + token + ";").contains("HttpOnly"))
                .filteredOn(cookie -> cookie.startsWith("parkio_refresh="))
                .hasSize(2);
    }
}
