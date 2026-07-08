package com.parkio.gateway.application.waitlist;

import java.time.Instant;

public record WaitlistExportRow(
        String email,
        String city,
        String role,
        String source,
        Instant createdAt,
        Instant consentTimestamp) {
}
