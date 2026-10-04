package com.parkio.gateway.presentation.waitlist;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistRateLimitExceededException;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import com.parkio.gateway.infrastructure.client.SessionEpochClient;
import com.parkio.gateway.infrastructure.client.UserStatusClient;
import com.parkio.gateway.infrastructure.client.UserStatusLookup;
import com.parkio.gateway.infrastructure.security.AuthenticatedUser;
import com.parkio.gateway.infrastructure.security.JwtTokenValidator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class WaitlistControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private WaitlistRateLimiter rateLimiter;

    @MockBean
    private WaitlistEmailSender emailSender;

    @MockBean
    private JwtTokenValidator tokenValidator;

    @MockBean
    private SessionEpochClient sessionEpochClient;

    @MockBean
    private UserStatusClient userStatusClient;

    private final AtomicReference<String> lastVerificationToken = new AtomicReference<>();
    private final AtomicReference<String> lastWithdrawToken = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
        when(sessionEpochClient.fetchCurrentEpoch(anyString())).thenReturn(Mono.just(0L));
        when(userStatusClient.fetchStatus(anyString())).thenReturn(Mono.just(UserStatusLookup.found("ACTIVE")));
        when(tokenValidator.validate("admin-token")).thenReturn(Mono.just(
                new AuthenticatedUser(UUID.randomUUID().toString(), "admin@parkio.test",
                        List.of("ADMIN"), "ACTIVE", 0L)));
        when(tokenValidator.validate("user-token")).thenReturn(Mono.just(
                new AuthenticatedUser(UUID.randomUUID().toString(), "user@parkio.test",
                        List.of("USER"), "ACTIVE", 0L)));
        when(tokenValidator.validate("moderator-token")).thenReturn(Mono.just(
                new AuthenticatedUser(UUID.randomUUID().toString(), "mod@parkio.test",
                        List.of("MODERATOR"), "ACTIVE", 0L)));
        when(tokenValidator.validate("super-token")).thenReturn(Mono.just(
                new AuthenticatedUser(UUID.randomUUID().toString(), "super@parkio.test",
                        List.of("SUPER_ADMIN"), "ACTIVE", 0L)));
        when(tokenValidator.validate("expired-token"))
                .thenReturn(Mono.error(new IllegalArgumentException("expired")));
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

    /**
     * CL-F14.3: resend must not reveal whether an address has a PENDING row. Only a
     * PENDING row reaches the provider, so during a provider outage a 503 for it next to
     * a 202 for an unknown address was an existence oracle. Both now get the same 202;
     * the failure is logged for operators and the row stays unsent, so a later resend
     * delivers once the provider is back.
     */
    @Test
    void resendDuringProviderOutageAnswersPendingAndUnknownAddressesAlike(CapturedOutput output) {
        String pending = "resend-pending@parkio.dev";
        String unknown = "resend-unknown@parkio.dev";
        org.mockito.Mockito.doThrow(new RuntimeException("provider down"))
                .when(emailSender)
                .sendConfirmation(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString());
        // Submit keeps its own contract: the row is saved and the failed send is a 503.
        webTestClient.post()
                .uri("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload(pending))
                .exchange()
                .expectStatus().isEqualTo(503);

        String pendingBody = resend(pending);
        String unknownBody = resend(unknown);

        org.assertj.core.api.Assertions.assertThat(pendingBody).isEqualTo(unknownBody);
        // The pending address really did hit the failing provider (submit + resend).
        org.mockito.Mockito.verify(emailSender, org.mockito.Mockito.times(2))
                .sendConfirmation(org.mockito.ArgumentMatchers.eq(pending), anyString(),
                        org.mockito.ArgumentMatchers.any(), anyString());
        org.mockito.Mockito.verify(emailSender, org.mockito.Mockito.never())
                .sendConfirmation(org.mockito.ArgumentMatchers.eq(unknown), anyString(),
                        org.mockito.ArgumentMatchers.any(), anyString());
        org.assertj.core.api.Assertions.assertThat(output)
                .contains("Waitlist resend answered 202 after a failed confirmation delivery");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM waitlist_interest WHERE email = ?", String.class, pending))
                .isEqualTo("PENDING");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM waitlist_interest WHERE email = ? AND verification_sent_at IS NULL",
                        Integer.class, pending))
                .isEqualTo(1);

        // Provider recovers: the next resend delivers without waiting for a cooldown.
        org.mockito.Mockito.doAnswer(invocation -> {
            lastVerificationToken.set(invocation.getArgument(1));
            return null;
        }).when(emailSender).sendConfirmation(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString());
        org.assertj.core.api.Assertions.assertThat(resend(pending)).isEqualTo(unknownBody);
        org.assertj.core.api.Assertions.assertThat(lastVerificationToken.get()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM waitlist_interest WHERE email = ? AND verification_sent_at IS NOT NULL",
                        Integer.class, pending))
                .isEqualTo(1);
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

        // WaitlistAdminSecurityWebFilter enforces ADMIN JWT on the local controller path.
        Integer confirmed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE status = 'CONFIRMED'",
                Integer.class);
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE status = 'PENDING'",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(confirmed).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(pending).isEqualTo(1);

        String body = webTestClient.get()
                .uri("/api/v1/waitlist/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Parkio-Export-Row-Limit", "50000")
                .expectHeader().valueEquals("X-Parkio-Export-Matching-Rows", "1")
                .expectHeader().valueEquals("X-Parkio-Export-Truncated", "false")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        // UTF-8 BOM first, for spreadsheet clients, then the header row.
        org.assertj.core.api.Assertions.assertThat(body)
                .startsWith("\uFEFFemail,fullName,city,role,source,createdAt,consentTimestamp\n");
        org.assertj.core.api.Assertions.assertThat(body).contains("will-confirm@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("only-pending@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("verification_token");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("email_hash");

        webTestClient.get()
                .uri("/api/v1/waitlist/export")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("MISSING_TOKEN");

        webTestClient.get()
                .uri("/api/v1/waitlist/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.get()
                .uri("/api/v1/waitlist/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer moderator-token")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.get()
                .uri("/api/v1/waitlist/export/")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/api/v1/waitlist/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer expired-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_TOKEN");

        webTestClient.get()
                .uri("/api/v1/waitlist/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer super-token")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void exportFiltersByConfirmationTimeAndRefusesRegistrationTimeFilters() {
        postAccepted("confirm-window@parkio.dev");
        webTestClient.post()
                .uri("/api/v1/waitlist/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + lastVerificationToken.get() + "\"}")
                .exchange()
                .expectStatus().isAccepted();

        String future = webTestClient.get()
                .uri(uri -> uri.path("/api/v1/waitlist/export")
                        .queryParam("confirmedFrom", "2999-01-01T00:00:00Z").build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Parkio-Export-Truncated", "false")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        org.assertj.core.api.Assertions.assertThat(future).doesNotContain("confirm-window@parkio.dev");

        String window = webTestClient.get()
                .uri(uri -> uri.path("/api/v1/waitlist/export")
                        .queryParam("confirmedFrom", "2000-01-01T00:00:00Z")
                        .queryParam("confirmedTo", "2999-01-01T00:00:00Z").build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        org.assertj.core.api.Assertions.assertThat(window).contains("confirm-window@parkio.dev");

        webTestClient.get()
                .uri(uri -> uri.path("/api/v1/waitlist/export")
                        .queryParam("createdFrom", "2000-01-01T00:00:00Z").build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("WAITLIST_EXPORT_FILTER_INVALID");
    }

    @Test
    void adminSummaryAndListReflectStatusesAndFilters() {
        postAccepted("pending-a@parkio.dev");
        postAccepted("confirm-a@parkio.dev");
        String confirmToken = lastVerificationToken.get();
        webTestClient.post()
                .uri("/api/v1/waitlist/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + confirmToken + "\"}")
                .exchange()
                .expectStatus().isAccepted();

        postAccepted("withdraw-a@parkio.dev");
        webTestClient.post()
                .uri("/api/v1/waitlist/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + lastWithdrawToken.get() + "\"}")
                .exchange()
                .expectStatus().isAccepted();

        webTestClient.get()
                .uri("/api/v1/waitlist/admin/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.pending").isEqualTo(1)
                .jsonPath("$.confirmed").isEqualTo(1)
                .jsonPath("$.withdrawn").isEqualTo(1)
                .jsonPath("$.total").isEqualTo(3);

        webTestClient.get()
                .uri("/api/v1/waitlist/admin/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer super-token")
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/api/v1/waitlist/admin/summary")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/api/v1/waitlist/admin/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/waitlist/admin")
                        .queryParam("status", "CONFIRMED")
                        .queryParam("page", "0")
                        .queryParam("size", "20")
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.content[0].email").isEqualTo("confirm-a@parkio.dev")
                .jsonPath("$.content[0].status").isEqualTo("CONFIRMED")
                .jsonPath("$.content[0].locale").isEqualTo("tr")
                .jsonPath("$.content[0].source").isEqualTo("parkio.dev-landing")
                .jsonPath("$.content[0].confirmedAt").exists()
                .jsonPath("$.content[0].verificationTokenHash").doesNotExist()
                .jsonPath("$.content[0].emailHash").doesNotExist()
                .jsonPath("$.content[0].ipHash").doesNotExist();

        webTestClient.get()
                .uri("/api/v1/waitlist/admin?status=WITHDRAWN")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.content[0].status").isEqualTo("WITHDRAWN")
                .jsonPath("$.content[0].email").value(email ->
                        org.assertj.core.api.Assertions.assertThat((String) email)
                                .startsWith("withdrawn-")
                                .endsWith("@invalid.local"));
    }

    /** CL-F34: page * size overflowed int into a negative OFFSET, which the database rejects. */
    @Test
    void adminListFarBeyondTheLastPageIsEmptyNotAServerError() {
        postAccepted("page-overflow@parkio.dev");

        webTestClient.get()
                .uri("/api/v1/waitlist/admin?page=" + Integer.MAX_VALUE + "&size=100")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(0)
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.page").isEqualTo(Integer.MAX_VALUE);
    }

    /** CL-F34: rows with the same created_at keep one order across pages (id breaks the tie). */
    @Test
    void adminListPagesRowsWithEqualCreationTimesInIdOrder() {
        java.sql.Timestamp createdAt = java.sql.Timestamp.from(Instant.parse("2026-09-01T10:00:00Z"));
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.fromString("00000000-0000-4000-8000-00000000000" + i);
            ids.add(id);
            jdbcTemplate.update("""
                    INSERT INTO waitlist_interest (id, email, email_hash, consent_timestamp, source, ip_hash, created_at)
                    VALUES (?, ?, ?, ?, 'parkio.dev-landing', 'ip-hash', ?)
                    """, id, "tie-" + i + "@parkio.dev", "tie-hash-" + i, createdAt, createdAt);
        }

        List<String> paged = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = webTestClient.get()
                    .uri("/api/v1/waitlist/admin?page=" + page + "&size=2")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();
            com.jayway.jsonpath.JsonPath.<List<String>>read(body, "$.content[*].id").forEach(paged::add);
        }

        // Database uuid order is unsigned (the canonical hex string order), not UUID.compareTo.
        List<String> expected = ids.stream().map(UUID::toString).sorted(java.util.Comparator.reverseOrder()).toList();
        org.assertj.core.api.Assertions.assertThat(paged).containsExactlyElementsOf(expected);
    }

    @Test
    void adminListEmptyStateIsUsable() {
        webTestClient.get()
                .uri("/api/v1/waitlist/admin")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content").isArray()
                .jsonPath("$.content.length()").isEqualTo(0)
                .jsonPath("$.totalElements").isEqualTo(0)
                .jsonPath("$.totalPages").isEqualTo(0);
    }

    private String resend(String email) {
        return webTestClient.post()
                .uri("/api/v1/waitlist/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"" + email + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
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
