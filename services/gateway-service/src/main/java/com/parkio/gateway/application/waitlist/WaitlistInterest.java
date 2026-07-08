package com.parkio.gateway.application.waitlist;

import java.time.Instant;
import java.util.UUID;

public record WaitlistInterest(
        UUID id,
        String email,
        String emailHash,
        Instant consentTimestamp,
        String city,
        String role,
        String source,
        String ipHash,
        String userAgentHash,
        Instant createdAt) {
}
