package com.parkio.auth.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.parkio.auth.domain.EmailLocale;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hamcrest.Matchers;
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
    private static final String TR_VERIFY_SUBJECT = "Parkio e-posta adresinizi doğrulayın";
    private static final String TR_RESET_SUBJECT = "Parkio şifrenizi sıfırlayın";

    private SimpleMeterRegistry registry;
    private MockRestServiceServer server;
    private ResendEmailSender sender;
    private TransactionalEmailProperties properties;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        properties = new TransactionalEmailProperties();
        properties.setProvider(TransactionalEmailProperties.Provider.RESEND);
        properties.setFrom("Parkio <verify@example.com>");
        properties.setReplyTo("support@example.com");
        properties.getResend().setApiKey(API_KEY);
        properties.getResend().setBaseUrl("https://api.resend.test");

        sender = createSender("https://app.example.com/verify-email", "https://app.example.com/reset-password");
    }

    private ResendEmailSender createSender(String verificationUrl, String resetUrl) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getResend().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + API_KEY)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        server = MockRestServiceServer.bindTo(builder).build();
        return new ResendEmailSender(builder.build(), properties, new EmailDeliveryMetrics(registry),
                verificationUrl, resetUrl);
    }

    @Test
    void sendsVerificationEmailInTurkishByDefault(CapturedOutput output) {
        server.expect(requestTo("https://api.resend.test/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.from").value("Parkio <verify@example.com>"))
                .andExpect(jsonPath("$.to[0]").value("user@example.com"))
                .andExpect(jsonPath("$.reply_to").value("support@example.com"))
                .andExpect(jsonPath("$.subject").value(TR_VERIFY_SUBJECT))
                .andExpect(jsonPath("$.text").value(Matchers.containsString("verify-email?token=" + TOKEN)))
                .andExpect(jsonPath("$.text").value(Matchers.not(Matchers.containsString(
                        "\n\nVerification token:\n" + TOKEN))))
                .andExpect(jsonPath("$.html").value(Matchers.not(Matchers.containsString("{{"))))
                .andExpect(jsonPath("$.html").value(Matchers.containsString("E-posta adresini doğrula")))
                .andRespond(withSuccess("{\"id\":\"email_123\"}", MediaType.APPLICATION_JSON));

        sender.sendVerificationLink("user@example.com", TOKEN);

        server.verify();
        assertThat(registry.counter("email_sent").count()).isEqualTo(1.0);
        assertThat(registry.counter("email_verification_sent").count()).isEqualTo(1.0);
        assertThat(registry.counter("email_failed").count()).isZero();
        assertThat(output).doesNotContain(TOKEN).doesNotContain(API_KEY);
    }

    @Test
    void sendsVerificationEmailInEnglishWhenRequested() {
        server.expect(requestTo("https://api.resend.test/emails"))
                .andExpect(jsonPath("$.subject").value("Verify your Parkio email"))
                .andExpect(jsonPath("$.html").value(Matchers.containsString("Verify email")))
                .andExpect(jsonPath("$.text").value(Matchers.containsString("verify-email?token=" + TOKEN)))
                .andRespond(withSuccess("{\"id\":\"email_en\"}", MediaType.APPLICATION_JSON));

        sender.sendVerificationLink("user@example.com", TOKEN, EmailLocale.EN);

        server.verify();
    }

    @Test
    void sendsPasswordResetInTurkishByDefault() {
        server.expect(requestTo("https://api.resend.test/emails"))
                .andExpect(jsonPath("$.subject").value(TR_RESET_SUBJECT))
                .andExpect(jsonPath("$.html").value(Matchers.containsString("Şifreyi sıfırla")))
                .andExpect(jsonPath("$.text").value(Matchers.containsString("reset-password?token=" + TOKEN)))
                .andRespond(withSuccess("{\"id\":\"email_reset_tr\"}", MediaType.APPLICATION_JSON));

        sender.sendResetLink("user@example.com", TOKEN);

        server.verify();
    }

    @Test
    void sendsPasswordResetInEnglishWhenRequested() {
        server.expect(requestTo("https://api.resend.test/emails"))
                .andExpect(jsonPath("$.subject").value("Reset your Parkio password"))
                .andExpect(jsonPath("$.html").value(Matchers.containsString("Reset password")))
                .andRespond(withSuccess("{\"id\":\"email_reset_en\"}", MediaType.APPLICATION_JSON));

        sender.sendResetLink("user@example.com", TOKEN, EmailLocale.EN);

        server.verify();
    }

    @Test
    void unsupportedLocaleFallsBackToTurkishVerification() {
        EmailLocale locale = EmailLocale.fromNullable("fr");
        assertThat(locale).isEqualTo(EmailLocale.TR);

        server.expect(requestTo("https://api.resend.test/emails"))
                .andExpect(jsonPath("$.subject").value(TR_VERIFY_SUBJECT))
                .andRespond(withSuccess("{\"id\":\"email_fallback\"}", MediaType.APPLICATION_JSON));

        sender.sendVerificationLink("user@example.com", TOKEN, locale);

        server.verify();
    }

    @Test
    void preservesTokenEncodingAndEscapesAmpersandInHtml() {
        sender = createSender(
                "https://app.example.com/verify-email?lang=tr",
                "https://app.example.com/reset-password");
        String tokenWithAmp = "tok&en";

        server.expect(requestTo("https://api.resend.test/emails"))
                .andExpect(jsonPath("$.text").value(Matchers.containsString("token=tok%26en")))
                .andExpect(jsonPath("$.html").value(Matchers.containsString("token=tok%26en")))
                .andExpect(jsonPath("$.html").value(Matchers.containsString("&amp;token=")))
                .andExpect(jsonPath("$.html").value(Matchers.not(Matchers.containsString("?lang=tr&token="))))
                .andRespond(withSuccess("{\"id\":\"email_escape\"}", MediaType.APPLICATION_JSON));

        sender.sendVerificationLink("user@example.com", tokenWithAmp, EmailLocale.EN);

        server.verify();
    }

    @Test
    void skipsResendForPriv001aSyntheticEmailWithoutCallingProvider(CapturedOutput output) {
        String synthetic = "priv001a-20260830120000abcd@priv001a.parkio.invalid";

        sender.sendVerificationLink(synthetic, TOKEN, EmailLocale.EN);

        server.verify(); // no expectations → no HTTP calls
        assertThat(registry.counter("email_sent").count()).isZero();
        assertThat(registry.counter("email_verification_sent").count()).isEqualTo(1.0);
        assertThat(registry.counter("email_failed").count()).isZero();
        assertThat(output).doesNotContain(TOKEN).doesNotContain(API_KEY).doesNotContain(synthetic);
        assertThat(ResendEmailSender.isPriv001aSyntheticEmail(synthetic)).isTrue();
        assertThat(ResendEmailSender.isPriv001aSyntheticEmail("user@gmail.com")).isFalse();
        assertThat(ResendEmailSender.isPriv001aSyntheticEmail("priv001a-abc@evil.priv001a.parkio.invalid"))
                .isFalse();
    }

    @Test
    void skipsPasswordResetResendForPriv001aSyntheticEmail() {
        String synthetic = "priv001a-20260830120000abcd@priv001a.parkio.invalid";

        sender.sendResetLink(synthetic, TOKEN, EmailLocale.EN);

        server.verify();
        assertThat(registry.counter("email_sent").count()).isZero();
        assertThat(registry.counter("email_failed").count()).isZero();
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