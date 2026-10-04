package com.parkio.gateway.application.waitlist;

import java.time.Instant;
import java.util.UUID;

/**
 * One confirmed subscription in the CSV export. {@code id} and {@code confirmedAt} position the
 * export's keyset paging; the CSV columns are the remaining fields.
 */
public record WaitlistExportRow(
        UUID id,
        Instant confirmedAt,
        String email,
        String fullName,
        String city,
        String role,
        String source,
        Instant createdAt,
        Instant consentTimestamp) {
}
