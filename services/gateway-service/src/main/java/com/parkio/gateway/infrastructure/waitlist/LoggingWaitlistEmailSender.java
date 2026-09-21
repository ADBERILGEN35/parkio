package com.parkio.gateway.infrastructure.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistHasher;
import com.parkio.gateway.application.waitlist.WaitlistProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default waitlist mail adapter. Logs only hashed identifiers — never raw email or tokens.
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
