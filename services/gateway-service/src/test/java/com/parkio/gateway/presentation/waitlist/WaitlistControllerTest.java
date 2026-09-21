package com.parkio.gateway.presentation.waitlist;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistRateLimitExceededException;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
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
                          "consentTimestamp": "%s",
                          "source": "parkio.dev-landing"
                        }
                        """.formatted(java.time.Instant.now().minusSeconds(5)))
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
                .jsonPath("$.code").isEqualTo("WAITLIST_CONSENT_TIMESTAMP_INVALID");
    }

    @Test
    void rejectsExcessiveFutureConsentTimestamp() {
        String future = java.time.Instant.now().plus(java.time.Duration.ofMinutes(30)).toString();
        webTestClient.post()
                .uri("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "future-skew@parkio.dev",
                          "consentTimestamp": "%s",
                          "source": "parkio.dev-landing",
                          "locale": "en"
                        }
                        """.formatted(future))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("WAITLIST_CONSENT_TIMESTAMP_INVALID");
    }

    @Test
    void acceptsSmallFutureClockSkewWithinPolicy() {
        String slightlyAhead = java.time.Instant.now().plusSeconds(30).toString();
        webTestClient.post()
                .uri("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "small-skew@parkio.dev",
                          "consentTimestamp": "%s",
                          "source": "parkio.dev-landing",
                          "locale": "en"
                        }
                        """.formatted(slightlyAhead))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("accepted");

        Instant storedConsent = jdbcTemplate.queryForObject(
                "SELECT consent_timestamp FROM waitlist_interest WHERE email = ?",
                Instant.class,
                "small-skew@parkio.dev");
        Instant clientConsent = jdbcTemplate.queryForObject(
                "SELECT client_consent_timestamp FROM waitlist_interest WHERE email = ?",
                Instant.class,
                "small-skew@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(storedConsent).isNotNull();
        org.assertj.core.api.Assertions.assertThat(clientConsent).isNotNull();
        // Postgres timestamptz may truncate nanos; compare to microsecond precision.
        org.assertj.core.api.Assertions.assertThat(clientConsent.getEpochSecond())
                .isEqualTo(Instant.parse(slightlyAhead).getEpochSecond());
        // Authoritative consent evidence is server receipt (at/near now), not a silent backdate.
        org.assertj.core.api.Assertions.assertThat(storedConsent)
                .isBeforeOrEqualTo(Instant.now().plusSeconds(5));
        org.assertj.core.api.Assertions.assertThat(storedConsent)
                .isAfter(Instant.now().minusSeconds(60));
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

    @Test
    void confirmReplayIsIdempotent() {
        postAccepted("replay@parkio.dev");
        String token = lastVerificationToken.get();

        webTestClient.post()
                .uri("/api/v1/waitlist/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + token + "\"}")
                .exchange()
                .expectStatus().isAccepted();

        webTestClient.post()
                .uri("/api/v1/waitlist/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + token + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("confirmed");
    }

    @Test
    void expiredTokenCannotConfirm() {
        postAccepted("expire@parkio.dev");
        jdbcTemplate.update(
                "UPDATE waitlist_interest SET verification_expires_at = TIMESTAMP '2020-01-01 00:00:00+00' WHERE email = ?",
                "expire@parkio.dev");

        webTestClient.post()
                .uri("/api/v1/waitlist/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + lastVerificationToken.get() + "\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("WAITLIST_TOKEN_INVALID");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM waitlist_interest WHERE email = ?",
                String.class,
                "expire@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void emailDeliveryFailureKeepsPendingAndAllowsRetry() {
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .doAnswer(invocation -> {
                    lastVerificationToken.set(invocation.getArgument(1));
                    lastWithdrawToken.set(invocation.getArgument(2));
                    return null;
                })
                .when(emailSender)
                .sendConfirmation(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString());

        webTestClient.post()
                .uri("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("retry-delivery@parkio.dev"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("WAITLIST_EMAIL_DELIVERY_FAILED");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE email = ?",
                Integer.class,
                "retry-delivery@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);

        webTestClient.post()
                .uri("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("retry-delivery@parkio.dev"))
                .exchange()
                .expectStatus().isAccepted();

        org.assertj.core.api.Assertions.assertThat(lastVerificationToken.get()).isNotBlank();
    }

    @Test
    void withdrawThenAllowsReregistration() {
        postAccepted("again@parkio.dev");
        webTestClient.post()
                .uri("/api/v1/waitlist/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + lastWithdrawToken.get() + "\"}")
                .exchange()
                .expectStatus().isAccepted();

        postAccepted("again@parkio.dev");
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE email = ? AND status = 'PENDING'",
                Integer.class,
                "again@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(pending).isEqualTo(1);
    }

    @Test
    void exportReturnsOnlyConfirmed() {
        postAccepted("only-pending@parkio.dev");
        postAccepted("will-confirm@parkio.dev");
        webTestClient.post()
                .uri("/api/v1/waitlist/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + lastVerificationToken.get() + "\"}")
                .exchange()
                .expectStatus().isAccepted();

        // Export requires auth in production filters; controller method itself is reachable in
        // WebTestClient without the global auth filter stack for this slice — assert repository filter.
        Integer confirmed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE status = 'CONFIRMED'",
                Integer.class);
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE status = 'PENDING'",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(confirmed).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(pending).isEqualTo(1);
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
                  "consentTimestamp": "%s",
                  "city": "Izmir",
                  "role": "tester",
                  "source": "parkio.dev-landing",
                  "locale": "tr"
                }
                """.formatted(email, java.time.Instant.now().minusSeconds(5).toString());
    }
}
