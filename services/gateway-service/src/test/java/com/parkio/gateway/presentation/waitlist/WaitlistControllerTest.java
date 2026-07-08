package com.parkio.gateway.presentation.waitlist;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.parkio.gateway.application.waitlist.WaitlistRateLimitExceededException;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
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
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());

        postAccepted("Driver@Parkio.dev");
        postAccepted("driver@parkio.dev");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE email = ?",
                Integer.class,
                "driver@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
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
                  "source": "parkio.dev-landing"
                }
                """.formatted(email);
    }
}
