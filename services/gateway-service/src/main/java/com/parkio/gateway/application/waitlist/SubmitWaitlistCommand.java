package com.parkio.gateway.application.waitlist;

import java.time.Instant;

public record SubmitWaitlistCommand(
        String email,
        Instant consentTimestamp,
        String city,
        String role,
        String source,
        String clientIp,
        String userAgent) {
}
