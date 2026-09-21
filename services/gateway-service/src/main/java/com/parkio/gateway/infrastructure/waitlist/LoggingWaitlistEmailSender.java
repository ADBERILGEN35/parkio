package com.parkio.gateway.infrastructure.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistHasher;
import com.parkio.gateway.application.waitlist.WaitlistProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local/test waitlist mail adapter. Logs only hashed identifiers — never raw email, tokens, or links.
 * Refuses to activate when {@code parkio.waitlist.email.allow-logging-provider=false}.
 */
@Component
@ConditionalOnProperty(prefix = "parkio.waitlist.email", name = "provider", havingValue = "logging", matchIfMissing = true)
public class LoggingWaitlistEmailSender implements WaitlistEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingWaitlistEmailSender.class);

    private final WaitlistHasher hasher;
    private final WaitlistProperties properties;

    public LoggingWaitlistEmailSender(WaitlistHasher hasher, WaitlistProperties properties) {
        this.hasher = hasher;
        this.properties = properties;
    }

    @PostConstruct
    void assertLoggingAllowed() {
        if (!properties.getEmail().isAllowLoggingProvider()) {
            throw new IllegalStateException(
                    "parkio.waitlist.email.provider=logging is blocked because "
                            + "parkio.waitlist.email.allow-logging-provider=false. "
                            + "Set PARKIO_WAITLIST_EMAIL_PROVIDER=resend for real delivery, "
                            + "or explicitly allow logging only in non-production environments.");
        }
        log.info(
                "Waitlist email provider=logging (hashed identifiers only; not real delivery). "
                        + "confirmBaseConfigured={}",
                properties.getConfirmBaseUrl() != null && !properties.getConfirmBaseUrl().isBlank());
    }

    @Override
    public void sendConfirmation(String email, String verificationToken, String withdrawToken, String locale) {
        log.info(
                "Waitlist confirmation queued; emailHash={}, locale={}, confirmBaseConfigured={}, verifyTokenPresent={}, withdrawTokenPresent={}",
                hasher.hash(email),
                locale,
                properties.getConfirmBaseUrl() != null && !properties.getConfirmBaseUrl().isBlank(),
                verificationToken != null && !verificationToken.isBlank(),
                withdrawToken != null && !withdrawToken.isBlank());
    }

    @Override
    public void sendWithdrawalNotice(String email, String locale) {
        log.info(
                "Waitlist withdrawal notice queued; emailHash={}, locale={}",
                hasher.hash(email),
                locale);
    }
}
