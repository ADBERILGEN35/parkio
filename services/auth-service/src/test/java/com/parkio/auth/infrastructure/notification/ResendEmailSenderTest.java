package com.parkio.auth.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ResendEmailSenderTest {

    private static final String API_KEY = "re_test_secret_key";
    private static final String TOKEN = "raw-verification-token";

    private SimpleMeterRegistry registry;
    private MockRestServiceServer server;
    private ResendEmailSender sender;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        TransactionalEmailProperties properties = new TransactionalEmailProperties();
        properties.setProvider(TransactionalEmailProperties.Provider.RESEND);
        properties.setFrom("Parkio <verify@example.com>");
        properties.setReplyTo("support@example.com");
        properties.getResend().setApiKey(API_KEY);
        properties.getResend().setBaseUrl("https://api.resend.test");

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getResend().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + API_KEY)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new ResendEmailSender(builder.build(), properties, new EmailDeliveryMetrics(registry),
                "https://app.example.com/verify-email", "https://app.example.com/reset-password");
    }

    @Test
    void sendsVerificationEmailThroughResend(CapturedOutput output) {
        server.expect(requestTo("https://api.resend.test/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.from").value("Parkio <verify@example.com>"))
                .andExpect(jsonPath("$.to[0]").value("user@example.com"))
                .andExpect(jsonPath("$.reply_to").value("support@example.com"))
                .andExpect(jsonPath("$.subject").value("Verify your Parkio email"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString(TOKEN)))
                .andRespond(withSuccess("{\"id\":\"email_123\"}", MediaType.APPLICATION_JSON));

        sender.sendVerificationLink("user@example.com", TOKEN);

        server.verify();
        assertThat(registry.counter("email_sent").count()).isEqualTo(1.0);
        assertThat(registry.counter("email_verification_sent").count()).isEqualTo(1.0);
        assertThat(registry.counter("email_failed").count()).isZero();
        assertThat(output).doesNotContain(TOKEN).doesNotContain(API_KEY);
    }

    @Test
    void recordsFailureWithoutLeakingTokenOrApiKey(CapturedOutput output) {
        server.expect(requestTo("https://api.resend.test/emails"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> sender.sendResetLink("user@example.com", TOKEN))
                .isInstanceOf(EmailDeliveryException.class);

        server.verify();
        assertThat(registry.counter("email_sent").count()).isZero();
        assertThat(registry.counter("email_failed").count()).isEqualTo(1.0);
        assertThat(registry.counter("email_verification_sent").count()).isZero();
        assertThat(output).doesNotContain(TOKEN).doesNotContain(API_KEY);
    }
}
