package com.parkio.auth.infrastructure.notification;

import com.parkio.auth.application.port.PasswordResetEmailSender;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local/dev-safe password reset sender. Production must keep token logging disabled
 * until an SMTP/provider adapter replaces this implementation.
 */
@Component
@ConditionalOnProperty(prefix = "parkio.email", name = "provider", havingValue = "logging", matchIfMissing = true)
public class LoggingPasswordResetEmailSender implements PasswordResetEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetEmailSender.class);

    private final String resetUrl;
    private final boolean logRawToken;
    private final EmailDeliveryMetrics metrics;

    public LoggingPasswordResetEmailSender(
            @Value("${parkio.security.password-reset.url:http://localhost:5173/reset-password}") String resetUrl,
            @Value("${parkio.security.password-reset.log-token:false}") boolean logRawToken,
            EmailDeliveryMetrics metrics) {
        this.resetUrl = resetUrl;
        this.logRawToken = logRawToken;
        this.metrics = metrics;
    }

    @Override
    public void sendResetLink(String email, String rawToken) {
        metrics.emailSent();
        if (!logRawToken) {
            log.info("Password reset requested; emailHash={}", Integer.toHexString(email.hashCode()));
            return;
        }
        String separator = resetUrl.contains("?") ? "&" : "?";
        String encoded = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        log.info("Password reset link for {}: {}{}token={}", email, resetUrl, separator, encoded);
    }
}
