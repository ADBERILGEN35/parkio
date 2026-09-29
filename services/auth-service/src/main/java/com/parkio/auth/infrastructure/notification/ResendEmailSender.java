package com.parkio.auth.infrastructure.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.PasswordResetEmailSender;
import com.parkio.auth.domain.EmailLocale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
        String link = AuthTransactionalEmailTemplates.pageUrl(verificationUrl, rawToken, locale);
        AuthTransactionalEmailTemplates.Copy copy = AuthTransactionalEmailTemplates.verification(locale, link);
        send(
                "email_verification",
                recipientEmail,
                rawToken,
                copy.subject(),
                AuthTransactionalEmailTemplates.renderText(copy),
                AuthTransactionalEmailTemplates.renderHtml(copy));
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
        String link = AuthTransactionalEmailTemplates.pageUrl(resetUrl, rawToken, locale);
        AuthTransactionalEmailTemplates.Copy copy = AuthTransactionalEmailTemplates.passwordReset(locale, link);
        send(
                "password_reset",
                recipientEmail,
                rawToken,
                copy.subject(),
                AuthTransactionalEmailTemplates.renderText(copy),
                AuthTransactionalEmailTemplates.renderHtml(copy));
    }

    private void send(
            String template,
            String recipientEmail,
            String rawToken,
            String subject,
            String text,
            String html) {
        String idempotencyKey = idempotencyKey(template, recipientEmail, rawToken);
        try {
            resend.post()
                    .uri("/emails")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(ResendEmailRequest.create(
                            email.getFrom(), recipientEmail, email.getReplyTo(), subject, text, html))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        throw new EmailDeliveryException(
                                "Resend rejected transactional email with status "
                                        + status.value()
                                        + " ("
                                        + statusClass(status)
                                        + ")",
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

    /**
     * Stable per exact send attempt for a given template/recipient/raw-token.
     * Retries of that same triple reuse the provider response (24h). A new
     * registration or resend that mints a different token intentionally uses a
     * new key. Fingerprint is a truncated SHA-256 of the raw token — never the
     * raw token itself.
     */
    static String idempotencyKey(String template, String recipientEmail, String rawToken) {
        return "auth/" + template + "/" + emailHash(recipientEmail) + "/" + tokenFingerprint(rawToken);
    }

    private static String statusClass(HttpStatusCode status) {
        int code = status.value();
        if (code == 401 || code == 403) {
            return "auth";
        }
        if (code == 429) {
            return "rate_limited";
        }
        if (code >= 500) {
            return "provider_5xx";
        }
        if (code >= 400) {
            return "client_4xx";
        }
        return "other";
    }

    private static String emailHash(String email) {
        return Integer.toHexString(email.hashCode());
    }

    private static String tokenFingerprint(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
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
