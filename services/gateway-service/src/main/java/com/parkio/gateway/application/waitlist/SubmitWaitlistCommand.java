package com.parkio.gateway.application.waitlist;

import java.time.Instant;

public record SubmitWaitlistCommand(
        String email,
        Instant consentTimestamp,
        String fullName,
        String city,
        String role,
        String source,
        String locale,
        String clientIp,
        String userAgent) {
}
