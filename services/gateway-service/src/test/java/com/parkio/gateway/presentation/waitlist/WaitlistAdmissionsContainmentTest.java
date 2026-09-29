package com.parkio.gateway.presentation.waitlist;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistHasher;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = "parkio.waitlist.admissions-enabled=false")
class WaitlistAdmissionsContainmentTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WaitlistHasher hasher;

    @MockBean
    private WaitlistRateLimiter rateLimiter;

    @MockBean
    private WaitlistEmailSender emailSender;

    @BeforeEach
    void setUp() {
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
        jdbcTemplate.update("DELETE FROM waitlist_interest");
    }

    @Test
    void submitRejectedWithoutWriteOrEmail() {
        webTestClient.post()
                .uri("/api/v1/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("blocked@parkio.dev"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("WAITLIST_ADMISSIONS_DISABLED");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE email = ?",
                Integer.class,
                "blocked@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(count).isZero();
        verify(emailSender, never())
                .sendConfirmation(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString());
        verify(rateLimiter, never()).check(anyString(), anyString());
    }

    @Test
    void resendRejectedWithoutEmail() {
        webTestClient.post()
                .uri("/api/v1/waitlist/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"blocked@parkio.dev\"}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("WAITLIST_ADMISSIONS_DISABLED");

        verify(emailSender, never())
                .sendConfirmation(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void confirmAndWithdrawRemainAvailableForPreseededTokens() {
        String verificationToken = "seed-verify-token-aaaaaaaaaaaaaaaaaaaaaa";
        String withdrawToken = "seed-withdraw-token-bbbbbbbbbbbbbbbbbbbbbb";
        jdbcTemplate.update(
                """
                INSERT INTO waitlist_interest (
                  id, email, email_hash, consent_timestamp, city, role, source, locale, status,
                  verification_token_hash, withdraw_token_hash, verification_expires_at,
                  verification_sent_at, resend_count, confirmed_at, withdrawn_at,
                  ip_hash, user_agent_hash, created_at
                ) VALUES (
                  ?, ?, ?, TIMESTAMP WITH TIME ZONE '2026-07-08 00:00:00+00', 'Izmir', 'tester', 'parkio.dev-landing', 'tr', 'PENDING',
                  ?, ?, TIMESTAMP WITH TIME ZONE '2099-01-01 00:00:00+00',
                  TIMESTAMP WITH TIME ZONE '2026-07-08 00:00:00+00', 0, NULL, NULL,
                  'ip', NULL, TIMESTAMP WITH TIME ZONE '2026-07-08 00:00:00+00'
                )
                """,
                UUID.randomUUID(),
                "seeded@parkio.dev",
                hasher.hash("seeded@parkio.dev"),
                hasher.hash(verificationToken),
                hasher.hash(withdrawToken));

        webTestClient.post()
                .uri("/api/v1/waitlist/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + verificationToken + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("confirmed");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM waitlist_interest WHERE email = ?",
                String.class,
                "seeded@parkio.dev");
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("CONFIRMED");

        webTestClient.post()
                .uri("/api/v1/waitlist/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + withdrawToken + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("withdrawn");

        String withdrawn = jdbcTemplate.queryForObject(
                "SELECT status FROM waitlist_interest WHERE email LIKE ?",
                String.class,
                "withdrawn-%@invalid.local");
        org.assertj.core.api.Assertions.assertThat(withdrawn).isEqualTo("WITHDRAWN");
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
