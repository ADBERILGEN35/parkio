package com.parkio.auth.infrastructure.notification;

import com.parkio.auth.application.port.EmailVerificationSender;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local/dev-safe verification sender. Production must keep token logging disabled
 * until an SMTP/provider adapter replaces this implementation.
 */
@Component
@ConditionalOnProperty(prefix = "parkio.email", name = "provider", havingValue = "logging", matchIfMissing = true)
public class LoggingEmailVerificationSender implements EmailVerificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailVerificationSender.class);

    private final String verificationUrl;
    private final boolean logRawToken;
    private final EmailDeliveryMetrics metrics;

    public LoggingEmailVerificationSender(
            @Value("${parkio.security.email-verification.url:http://localhost:5173/verify-email}") String verificationUrl,
            @Value("${parkio.security.email-verification.log-token:false}") boolean logRawToken,
            EmailDeliveryMetrics metrics) {
        this.verificationUrl = verificationUrl;
        this.logRawToken = logRawToken;
        this.metrics = metrics;
    }

    @Override
    public void sendVerificationLink(String email, String rawToken) {
        metrics.emailSent();
        metrics.verificationSent();
        if (!logRawToken) {
            log.info("Email verification requested; emailHash={}", Integer.toHexString(email.hashCode()));
            return;
        }
        String separator = verificationUrl.contains("?") ? "&" : "?";
        String encoded = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        log.info("Email verification link for {}: {}{}token={}", email, verificationUrl, separator, encoded);
    }
}
