package com.parkio.gateway.presentation.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import com.parkio.gateway.infrastructure.client.SessionEpochClient;
import com.parkio.gateway.infrastructure.client.UserStatusClient;
import com.parkio.gateway.infrastructure.security.JwtTokenValidator;
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

/**
 * CL-F18 compatibility mode ({@code parkio.waitlist.consent-required=false}, the deploy window in
 * which an older marketing bundle without the consent fields is still live): an old payload is
 * accepted end to end and stored as {@code unversioned-client}; a versioned payload keeps its
 * version; an explicit refusal and an unknown version are refused in this mode too.
 */
@SpringBootTest(properties = "parkio.waitlist.consent-required=false")
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class WaitlistControllerCompatibilityModeTest {

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

    @BeforeEach
    void setUp() {
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
        doAnswer(invocation -> null).when(emailSender).sendConfirmation(anyString(), anyString(), any(), anyString());
        jdbcTemplate.update("DELETE FROM waitlist_interest");
    }

    @Test
    void anOldPayloadWithoutConsentFieldsIsAcceptedAndStoredAsUnversionedClient() {
        post("""
                {"email": "old-bundle@parkio.dev", "consentTimestamp": "%s", "source": "parkio.dev-landing", "locale": "en"}
                """.formatted(Instant.now().minusSeconds(5)))
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("accepted");

        assertThat(storedVersion("old-bundle@parkio.dev")).isEqualTo("unversioned-client");
    }

    @Test
    void aVersionedPayloadKeepsItsVersionAndRefusalsStayRefused() {
        String now = Instant.now().minusSeconds(5).toString();
        post("""
                {"email": "new-bundle@parkio.dev", "consentTimestamp": "%s", "consent": true,
                 "consentTextVersion": "waitlist-consent-v1", "source": "parkio.dev-landing"}
                """.formatted(now))
                .expectStatus().isAccepted();
        assertThat(storedVersion("new-bundle@parkio.dev")).isEqualTo("waitlist-consent-v1");

        post("""
                {"email": "refused@parkio.dev", "consentTimestamp": "%s", "consent": false, "source": "parkio.dev-landing"}
                """.formatted(now))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("WAITLIST_CONSENT_REQUIRED");
        post("""
                {"email": "unknown@parkio.dev", "consentTimestamp": "%s", "consent": true,
                 "consentTextVersion": "legacy-unversioned", "source": "parkio.dev-landing"}
                """.formatted(now))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("WAITLIST_CONSENT_VERSION_INVALID");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_interest WHERE email IN ('refused@parkio.dev', 'unknown@parkio.dev')", Integer.class);
        assertThat(rows).isZero();
    }

    private WebTestClient.ResponseSpec post(String body) {
        return webTestClient.post().uri("/api/v1/waitlist").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private String storedVersion(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT consent_text_version FROM waitlist_interest WHERE email = ?", String.class, email);
    }
}
