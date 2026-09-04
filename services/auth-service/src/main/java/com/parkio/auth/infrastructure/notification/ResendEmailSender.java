package com.parkio.auth.infrastructure.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.PasswordResetEmailSender;
import com.parkio.auth.domain.EmailLocale;
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

    /**
     * Reserved PRIV-001A operator acceptance domain ({@code @priv001a.parkio.invalid}).
     * Skipping Resend here prevents real-provider delivery / billing for synthetic
     * principals. This is <strong>not</strong> an email-verification bypass: accounts
     * remain {@code PENDING_VERIFICATION} until the operator harness performs its
     * allowlisted verification-state mutation (or a real token is verified).
     */
    static final String PRIV001A_SYNTHETIC_EMAIL_SUFFIX = "@priv001a.parkio.invalid";

    static boolean isPriv001aSyntheticEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith(PRIV001A_SYNTHETIC_EMAIL_SUFFIX)
                && normalized.matches("^priv001a-[a-z0-9]{6,64}@priv001a\\.parkio\\.invalid$");
    }

    @Override
    public void sendVerificationLink(String recipientEmail, String rawToken, EmailLocale locale) {
        if (isPriv001aSyntheticEmail(recipientEmail)) {
            metrics.verificationSent();
            log.info(
                    "Skipping Resend for PRIV-001A synthetic principal; template=email_verification, emailHash={}",
                    emailHash(recipientEmail));
            return;
        }
        String link = appendToken(verificationUrl, rawToken);
        TransactionalEmailCopy.Copy copy = TransactionalEmailCopy.verification(locale);
        String text = """
                %s

                %s

                %s

                %s
                """.formatted(copy.heading(), copy.body(), link, copy.ignoreNote());
        String html = """
                <div style="font-family:Inter,Segoe UI,sans-serif;line-height:1.5;color:#1a1c1e;max-width:560px">
                  <h1 style="font-size:20px;margin:0 0 12px">%s</h1>
                  <p style="margin:0 0 16px">%s</p>
                  <p style="margin:0 0 20px"><a href="%s" style="display:inline-block;background:#0061a4;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:999px;font-weight:600">%s</a></p>
                  <p style="margin:0;font-size:13px;color:#5c5f66">%s<br><a href="%s">%s</a></p>
                  <p style="margin:20px 0 0;font-size:13px;color:#5c5f66">%s</p>
                </div>
                """.formatted(
                escapeHtml(copy.heading()),
                escapeHtml(copy.body()),
                escapeHtml(link),
                escapeHtml(copy.cta()),
                escapeHtml(copy.fallbackLinkIntro()),
                escapeHtml(link),
                escapeHtml(link),
                escapeHtml(copy.ignoreNote()));
        send("email_verification", recipientEmail, copy.subject(), text, html);
        metrics.verificationSent();
    }

    @Override
    public void sendResetLink(String recipientEmail, String rawToken, EmailLocale locale) {
        if (isPriv001aSyntheticEmail(recipientEmail)) {
            log.info(
                    "Skipping Resend for PRIV-001A synthetic principal; template=password_reset, emailHash={}",
                    emailHash(recipientEmail));
            return;
        }
        String link = appendToken(resetUrl, rawToken);
        TransactionalEmailCopy.Copy copy = TransactionalEmailCopy.passwordReset(locale);
        String text = """
                %s

                %s

                %s

                %s
                """.formatted(copy.heading(), copy.body(), link, copy.ignoreNote());
        String html = """
                <div style="font-family:Inter,Segoe UI,sans-serif;line-height:1.5;color:#1a1c1e;max-width:560px">
                  <h1 style="font-size:20px;margin:0 0 12px">%s</h1>
                  <p style="margin:0 0 16px">%s</p>
                  <p style="margin:0 0 20px"><a href="%s" style="display:inline-block;background:#0061a4;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:999px;font-weight:600">%s</a></p>
                  <p style="margin:0;font-size:13px;color:#5c5f66">%s<br><a href="%s">%s</a></p>
                  <p style="margin:20px 0 0;font-size:13px;color:#5c5f66">%s</p>
                </div>
                """.formatted(
                escapeHtml(copy.heading()),
                escapeHtml(copy.body()),
                escapeHtml(link),
                escapeHtml(copy.cta()),
                escapeHtml(copy.fallbackLinkIntro()),
                escapeHtml(link),
                escapeHtml(link),
                escapeHtml(copy.ignoreNote()));
        send("password_reset", recipientEmail, copy.subject(), text, html);
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
