package com.parkio.gateway.application.waitlist;

import java.time.Instant;
import java.util.UUID;

/**
 * Operator-visible waitlist row. Tokens, hashes, and abuse signals are intentionally omitted.
 * Withdrawn rows keep the scrubbed placeholder email stored after withdrawal.
 */
public record WaitlistAdminEntry(
        UUID id,
        String email,
        String fullName,
        WaitlistStatus status,
        String locale,
        String source,
        Instant createdAt,
        Instant confirmedAt,
        Instant withdrawnAt,
        /** Server receipt time of the consented submission (CL-F18). */
        Instant consentTimestamp,
        /** Consent text version the subscriber accepted, or a sentinel for unversioned rows (CL-F18). */
        String consentTextVersion) {
}
