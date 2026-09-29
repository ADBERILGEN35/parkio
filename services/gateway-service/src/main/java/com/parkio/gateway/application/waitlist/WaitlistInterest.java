package com.parkio.gateway.application.waitlist;

import java.time.Instant;
import java.util.UUID;

public record WaitlistInterest(
        UUID id,
        String email,
        String emailHash,
        Instant consentTimestamp,
        Instant clientConsentTimestamp,
        String fullName,
        String city,
        String role,
        String source,
        String locale,
        WaitlistStatus status,
        String verificationTokenHash,
        String withdrawTokenHash,
        Instant verificationExpiresAt,
        Instant verificationSentAt,
        int resendCount,
        Instant confirmedAt,
        Instant withdrawnAt,
        String ipHash,
        String userAgentHash,
        Instant createdAt) {
}
