package com.parkio.gateway.infrastructure.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistEmailDeliveryException;
import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistHasher;
import com.parkio.gateway.application.waitlist.WaitlistProperties;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resend-backed waitlist mailer (https://resend.com/docs/api-reference/emails).
 * Activated only when {@code parkio.waitlist.email.provider=resend}.
 */
@Component
@ConditionalOnProperty(prefix = "parkio.waitlist.email", name = "provider", havingValue = "resend")
public class ResendWaitlistEmailSender implements WaitlistEmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendWaitlistEmailSender.class);

    private final RestClient restClient;
    private final WaitlistProperties properties;
    private final WaitlistHasher hasher;

    public ResendWaitlistEmailSender(
            RestClient.Builder waitlistRestClientBuilder,
            WaitlistProperties properties,
            WaitlistHasher hasher) {
        this.properties = properties;
        this.hasher = hasher;
        this.restClient = waitlistRestClientBuilder
                .baseUrl(properties.getEmail().getResendBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getEmail().getResendApiKey())
                .build();
    }

    @PostConstruct
    void assertConfigured() {
        WaitlistProperties.Email email = properties.getEmail();
        if (email.getResendApiKey() == null || email.getResendApiKey().isBlank()) {
            throw new IllegalStateException(
                    "parkio.waitlist.email.provider=resend requires PARKIO_WAITLIST_RESEND_API_KEY (or PARKIO_RESEND_API_KEY).");
        }
        if (email.getFrom() == null || email.getFrom().isBlank()) {
            throw new IllegalStateException(
                    "parkio.waitlist.email.provider=resend requires PARKIO_WAITLIST_EMAIL_FROM (or PARKIO_EMAIL_FROM).");
        }
    }

    @Override
    public void sendConfirmation(String email, String verificationToken, String withdrawToken, String locale) {
        String lang = WaitlistEmailTemplates.normalizeLocale(locale);
        String confirmUrl = WaitlistEmailTemplates.pageUrl(properties.getConfirmBaseUrl(), verificationToken, lang);
        String withdrawUrl = withdrawToken == null || withdrawToken.isBlank()
                ? null
                : WaitlistEmailTemplates.pageUrl(properties.getWithdrawBaseUrl(), withdrawToken, lang);
        WaitlistEmailTemplates.Copy copy = WaitlistEmailTemplates.confirmation(lang, confirmUrl, withdrawUrl);
        send(email, copy.subject(), WaitlistEmailTemplates.renderText(copy), WaitlistEmailTemplates.renderHtml(copy));
        log.info("Waitlist confirmation emailed; emailHash={}, locale={}", hasher.hash(email), lang);
    }

    @Override
    public void sendWithdrawalNotice(String email, String locale) {
        String lang = WaitlistEmailTemplates.normalizeLocale(locale);
        WaitlistEmailTemplates.Copy copy = WaitlistEmailTemplates.withdrawal(lang);
        send(email, copy.subject(), WaitlistEmailTemplates.renderText(copy), WaitlistEmailTemplates.renderHtml(copy));
        log.info("Waitlist withdrawal emailed; emailHash={}, locale={}", hasher.hash(email), lang);
    }

    private void send(String email, String subject, String text, String html) {
        WaitlistProperties.Email emailProps = properties.getEmail();
        Map<String, Object> body = new HashMap<>();
        body.put("from", emailProps.getFrom());
        body.put("to", List.of(email));
        body.put("subject", subject);
        body.put("text", text);
        body.put("html", html);
        if (emailProps.getReplyTo() != null && !emailProps.getReplyTo().isBlank()) {
            body.put("reply_to", emailProps.getReplyTo());
        }
        try {
            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new WaitlistEmailDeliveryException("WAITLIST_EMAIL_DELIVERY_FAILED", ex);
        }
    }
}
