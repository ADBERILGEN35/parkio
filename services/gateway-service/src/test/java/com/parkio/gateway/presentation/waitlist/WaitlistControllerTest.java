package com.parkio.gateway.presentation.waitlist;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistRateLimitExceededException;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class WaitlistControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private WaitlistRateLimiter rateLimiter;

    @MockBean
    private WaitlistEmailSender emailSender;

    private final AtomicReference<String> lastVerificationToken = new AtomicReference<>();
    private final AtomicReference<String> lastWithdrawToken = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
        lastVerificationToken.set(null);
        lastWithdrawToken.set(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            lastVerificationToken.set(invocation.getArgument(1));
            lastWithdrawToken.set(invocation.getArgument(2));
            return null;
        }).when(emailSender).sendConfirmation(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString());
        jdbcTemplate.update("DELETE FROM waitlist_interest");
    }

    @Test
    void rejectsInvalidEmail() {
        webTestClient.post()
                .uri("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "not-an-email",
                          "consentTimestamp": "2026-07-08T00:00:00Z",
                          "source": "parkio.dev-landing"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void rejectsMissingConsentTimestamp() {
        webTestClient.post()
                .uri("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "driver@parkio.dev",
                          "source": "parkio.dev-landing"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void duplicateEmailReturnsAcceptedWithoutCreatingSecondRow() {
        postAccepted("Driver@Parkio.dev");
        postAccepted("driver@parkio.dev");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE email = ?",
                Integer.class,
                "driver@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(lastVerificationToken.get()).isNotBlank();
    }

    @Test
    void submitCreatesPendingUntilConfirmed() {
        postAccepted("pending@parkio.dev");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM waitlist_interest WHERE email = ?",
                String.class,
                "pending@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("PENDING");

        webTestClient.post()
                .uri("/api/v1/waitlist/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + lastVerificationToken.get() + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("confirmed");

        String confirmed = jdbcTemplate.queryForObject(
                "SELECT status FROM waitlist_interest WHERE email = ?",
                String.class,
                "pending@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(confirmed).isEqualTo("CONFIRMED");
    }

    @Test
    void getStyleConfirmIsNotExposed() {
        webTestClient.get()
                .uri("/api/v1/waitlist/confirm?token=abc")
                .exchange()
                .expectStatus().isEqualTo(405);
    }

    @Test
    void withdrawRemovesSignup() {
        postAccepted("leave@parkio.dev");
        String withdrawToken = lastWithdrawToken.get();
        org.assertj.core.api.Assertions.assertThat(withdrawToken).isNotBlank();

        webTestClient.post()
                .uri("/api/v1/waitlist/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + withdrawToken + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("withdrawn");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM waitlist_interest WHERE email LIKE ?",
                String.class,
                "withdrawn-%@invalid.local");
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("WITHDRAWN");
    }

    @Test
    void invalidConfirmTokenDoesNotLeak() {
        webTestClient.post()
                .uri("/api/v1/waitlist/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"not-a-real-token\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("WAITLIST_TOKEN_INVALID");
    }

    @Test
    void rateLimitFailureReturns429WithoutPersisting() {
        when(rateLimiter.check(anyString(), anyString()))
                .thenReturn(Mono.error(new WaitlistRateLimitExceededException()));

        webTestClient.post()
                .uri("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("limited@parkio.dev"))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMITED");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE email = ?",
                Integer.class,
                "limited@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(count).isZero();
    }

    private void postAccepted(String email) {
        webTestClient.post()
                .uri("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload(email))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("accepted");
    }

    private static String payload(String email) {
        return """
                {
                  "email": "%s",
                  "consentTimestamp": "2026-07-08T00:00:00Z",
                  "city": "Izmir",
                  "role": "tester",
                  "source": "parkio.dev-landing",
                  "locale": "tr"
                }
                """.formatted(email);
    }
}
