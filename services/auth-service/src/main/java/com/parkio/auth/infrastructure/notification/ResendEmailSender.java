package com.parkio.auth.infrastructure.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.PasswordResetEmailSender;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Resend-backed transactional email sender. Auth flows depend only on sender ports. */
@Component
@ConditionalOnProperty(prefix = "parkio.email", name = "provider", havingValue = "resend")
public class ResendEmailSender implements EmailVerificationSender, PasswordResetEmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private final RestClient resend;
    private final TransactionalEmailProperties email;
    private final EmailDeliveryMetrics metrics;
    private final String verificationUrl;
    private final String resetUrl;

    public ResendEmailSender(RestClient resendRestClient,
                             TransactionalEmailProperties email,
                             EmailDeliveryMetrics metrics,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${parkio.security.email-verification.url:http://localhost:5173/verify-email}")
                                     String verificationUrl,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${parkio.security.password-reset.url:http://localhost:5173/reset-password}")
                                     String resetUrl) {
        this.resend = resendRestClient;
        this.email = email;
        this.metrics = metrics;
        this.verificationUrl = verificationUrl;
        this.resetUrl = resetUrl;
    }

    @Override
    public void sendVerificationLink(String recipientEmail, String rawToken) {
        String link = appendToken(verificationUrl, rawToken);
        String subject = "Verify your Parkio email";
        String text = """
                Verify your Parkio email:

                %s

                Verification token:
                %s

                If you did not create a Parkio account, you can ignore this email.
                """.formatted(link, rawToken);
        String html = """
                <p>Verify your Parkio email:</p>
                <p><a href="%s">Verify email</a></p>
                <p>Verification token:</p>
                <p><code>%s</code></p>
                <p>If you did not create a Parkio account, you can ignore this email.</p>
                """.formatted(escapeHtml(link), escapeHtml(rawToken));
        send("email_verification", recipientEmail, subject, text, html);
        metrics.verificationSent();
    }

    @Override
    public void sendResetLink(String recipientEmail, String rawToken) {
        String link = appendToken(resetUrl, rawToken);
        String subject = "Reset your Parkio password";
        String text = """
                Reset your Parkio password:

                %s

                Password reset token:
                %s

                If you did not request a password reset, you can ignore this email.
                """.formatted(link, rawToken);
        String html = """
                <p>Reset your Parkio password:</p>
                <p><a href="%s">Reset password</a></p>
                <p>Password reset token:</p>
                <p><code>%s</code></p>
                <p>If you did not request a password reset, you can ignore this email.</p>
                """.formatted(escapeHtml(link), escapeHtml(rawToken));
        send("password_reset", recipientEmail, subject, text, html);
    }

    private void send(String template, String recipientEmail, String subject, String text, String html) {
        try {
            resend.post()
                    .uri("/emails")
                    .body(ResendEmailRequest.create(
                            email.getFrom(), recipientEmail, email.getReplyTo(), subject, text, html))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new EmailDeliveryException(
                                "Resend rejected transactional email with status " + response.getStatusCode(),
                                null);
                    })
                    .toBodilessEntity();
            metrics.emailSent();
            log.info("Transactional email accepted; provider=resend, template={}, emailHash={}",
                    template, emailHash(recipientEmail));
        } catch (EmailDeliveryException ex) {
            metrics.emailFailed();
            log.warn("Transactional email rejected; provider=resend, template={}, emailHash={}, reason={}",
                    template, emailHash(recipientEmail), ex.getMessage());
            throw ex;
        } catch (RestClientException ex) {
            metrics.emailFailed();
            log.warn("Transactional email failed; provider=resend, template={}, emailHash={}, exception={}",
                    template, emailHash(recipientEmail), ex.getClass().getSimpleName());
            throw new EmailDeliveryException("Resend transactional email delivery failed", ex);
        }
    }

    private static String appendToken(String baseUrl, String rawToken) {
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    private static String emailHash(String email) {
        return Integer.toHexString(email.hashCode());
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ResendEmailRequest(
            String from,
            List<String> to,
            @JsonProperty("reply_to")
            String replyTo,
            String subject,
            String text,
            String html) {

        static ResendEmailRequest create(String from,
                                         String to,
                                         String replyTo,
                                         String subject,
                                         String text,
                                         String html) {
            return new ResendEmailRequest(from, List.of(to), replyTo, subject, text, html);
        }
    }
}
