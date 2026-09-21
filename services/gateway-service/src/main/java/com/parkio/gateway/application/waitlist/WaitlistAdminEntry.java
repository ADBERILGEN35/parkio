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
        WaitlistStatus status,
        String locale,
        String source,
        Instant createdAt,
        Instant confirmedAt,
        Instant withdrawnAt) {
}
