package com.parkio.gateway.infrastructure.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistHasher;
import com.parkio.gateway.application.waitlist.WaitlistProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Optional Resend-backed waitlist mailer. Activated only when explicitly configured.
 */
@Component
@ConditionalOnProperty(prefix = "parkio.waitlist.email", name = "provider", havingValue = "resend")
public class ResendWaitlistEmailSender implements WaitlistEmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendWaitlistEmailSender.class);

    private final RestClient restClient;
    private final WaitlistProperties properties;
    private final WaitlistHasher hasher;

    public ResendWaitlistEmailSender(
            RestClient.Builder restClientBuilder,
            WaitlistProperties properties,
            WaitlistHasher hasher) {
        this.properties = properties;
        this.hasher = hasher;
        this.restClient = restClientBuilder
                .baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer " + properties.getEmail().getResendApiKey())
                .build();
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
        restClient.post()
                .uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "from", emailProps.getFrom(),
                        "to", List.of(email),
                        "reply_to", emailProps.getReplyTo() == null ? "" : emailProps.getReplyTo(),
                        "subject", subject,
                        "text", text))
                .retrieve()
                .toBodilessEntity();
    }

    private static String joinUrl(String base, String rawToken) {
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + "token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }
}
