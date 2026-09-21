package com.parkio.gateway.infrastructure.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistEmailDeliveryException;
import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistHasher;
import com.parkio.gateway.application.waitlist.WaitlistProperties;
import jakarta.annotation.PostConstruct;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        String confirmUrl = joinUrl(properties.getConfirmBaseUrl(), verificationToken);
        boolean turkish = !"en".equalsIgnoreCase(locale);
        String subject = turkish
                ? "Parkio kayıt bildirim listesini onaylayın"
                : "Confirm your Parkio registration waitlist signup";
        StringBuilder text = new StringBuilder();
        if (turkish) {
            text.append("Parkio kayıtlar açıldığında sizi bilgilendirmek için e-posta adresinizi doğrulamanız gerekiyor.\n\n");
            text.append("Onaylamak için bu sayfayı açın ve Onayla düğmesine basın:\n");
            text.append(confirmUrl).append("\n\n");
            if (withdrawToken != null && !withdrawToken.isBlank()) {
                text.append("Listeden çıkmak için:\n");
                text.append(joinUrl(properties.getWithdrawBaseUrl(), withdrawToken)).append("\n\n");
            }
            text.append("Bu isteği siz yapmadıysanız bu e-postayı yok sayabilirsiniz.");
        } else {
            text.append("Please confirm your email so Parkio can notify you when registrations open.\n\n");
            text.append("Open this page and press Confirm:\n");
            text.append(confirmUrl).append("\n\n");
            if (withdrawToken != null && !withdrawToken.isBlank()) {
                text.append("To withdraw:\n");
                text.append(joinUrl(properties.getWithdrawBaseUrl(), withdrawToken)).append("\n\n");
            }
            text.append("If you did not request this, you can ignore this email.");
        }
        send(email, subject, text.toString());
        log.info("Waitlist confirmation emailed; emailHash={}, locale={}", hasher.hash(email), locale);
    }

    @Override
    public void sendWithdrawalNotice(String email, String locale) {
        boolean turkish = !"en".equalsIgnoreCase(locale);
        String subject = turkish
                ? "Parkio bildirim listesi kaydınız silindi"
                : "Your Parkio waitlist signup was removed";
        String text = turkish
                ? "E-posta adresiniz Parkio kayıt bildirim listesinden silindi."
                : "Your email was removed from the Parkio registration waitlist.";
        send(email, subject, text);
        log.info("Waitlist withdrawal emailed; emailHash={}, locale={}", hasher.hash(email), locale);
    }

    private void send(String email, String subject, String text) {
        WaitlistProperties.Email emailProps = properties.getEmail();
        Map<String, Object> body = new HashMap<>();
        body.put("from", emailProps.getFrom());
        body.put("to", List.of(email));
        body.put("subject", subject);
        body.put("text", text);
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

    private static String joinUrl(String base, String rawToken) {
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + "token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }
}
