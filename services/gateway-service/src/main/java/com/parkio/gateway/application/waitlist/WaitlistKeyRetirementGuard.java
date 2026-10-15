package com.parkio.gateway.application.waitlist;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * CL-N08 / R-06: refuse gateway start when waitlist HMAC key retirement would strand
 * identity rows or confirm/unsubscribe token digests.
 *
 * <p>Email previous may be removed only after every active {@code email_hash} matches the
 * current email secret. In the default shared-secret configuration, email-secret rotation
 * also re-keys token digests — so a dedicated {@code hash-token-secret} is required before
 * {@code hash-secret-previous} may be set. Email stale checks page by id so the guard never
 * materializes the full interest table in the JVM.
 */
@Component
public class WaitlistKeyRetirementGuard {

    private static final Logger log = LoggerFactory.getLogger(WaitlistKeyRetirementGuard.class);

    /** Page size for stale email-digest scans (bounded heap). */
    static final int EMAIL_DIGEST_PAGE_SIZE = 200;

    private final JdbcTemplate jdbc;
    private final WaitlistHasher hasher;
    private final WaitlistProperties properties;

    public WaitlistKeyRetirementGuard(JdbcTemplate jdbc, WaitlistHasher hasher, WaitlistProperties properties) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.properties = properties;
    }

    @PostConstruct
    public void assertEmailKeyRetirementSafe() {
        assertDedicatedTokenSecretWhenEmailPreviousConfigured();

        String previous = properties.getHashSecretPrevious();
        if (previous != null && !previous.isBlank()) {
            return;
        }
        Long unrehashed = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM waitlist_interest
                WHERE email_hash NOT LIKE 'withdrawn-%'
                """,
                Long.class);
        if (unrehashed == null || unrehashed == 0L) {
            return;
        }
        long stale = countStaleEmailDigests();
        if (stale > 0) {
            throw new IllegalStateException(
                    "parkio.waitlist.hash-secret-previous is required: " + stale
                            + " waitlist_interest row(s) still use a non-current email_hash. "
                            + "Restore the previous secret or migrate identity hashes before removing it.");
        }
        log.info("Waitlist email-key previous unset; {} active rows already on current digest", unrehashed);
    }

    /**
     * R-06: blank token secret falls back to the email secret, so email rotation with
     * {@code hash-secret-previous} would leave outstanding confirm/unsubscribe digests
     * depending on that previous key even after every {@code email_hash} is rehashed.
     */
    private void assertDedicatedTokenSecretWhenEmailPreviousConfigured() {
        String emailPrevious = properties.getHashSecretPrevious();
        if (emailPrevious == null || emailPrevious.isBlank()) {
            return;
        }
        String tokenSecret = properties.getHashTokenSecret();
        if (tokenSecret == null || tokenSecret.isBlank()) {
            throw new IllegalStateException(
                    "parkio.waitlist.hash-token-secret is required while hash-secret-previous is set. "
                            + "Confirm/unsubscribe digests share the email secret when the token secret is blank, "
                            + "so retiring the previous email secret after rehash strands outstanding links. "
                            + "Set PARKIO_WAITLIST_HASH_TOKEN_SECRET to the pre-rotation waitlist hash secret "
                            + "(or another stable ≥32-char secret) before email-secret rotation.");
        }
        if (tokenSecret.equals(properties.getHashSecret())) {
            throw new IllegalStateException(
                    "parkio.waitlist.hash-token-secret must differ from parkio.waitlist.hash-secret "
                            + "while hash-secret-previous is set. A token secret equal to the current email secret "
                            + "does not keep outstanding links verifiable across email-key retirement; use a "
                            + "dedicated token secret (typically the pre-rotation email hash secret).");
        }
    }

    /** Scan email digests in id-ordered pages so startup never loads the full table. */
    private long countStaleEmailDigests() {
        long stale = 0L;
        UUID afterId = null;
        while (true) {
            final UUID cursor = afterId;
            List<EmailDigestRow> page = jdbc.query(
                    """
                    SELECT id, email, email_hash FROM waitlist_interest
                    WHERE email_hash NOT LIKE 'withdrawn-%'
                      AND (?::uuid IS NULL OR id > ?)
                    ORDER BY id
                    LIMIT ?
                    """,
                    (rs, rowNum) -> new EmailDigestRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("email_hash")),
                    cursor,
                    cursor,
                    EMAIL_DIGEST_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (EmailDigestRow row : page) {
                if (!hasher.isCurrentEmailHash(row.email(), row.emailHash())) {
                    stale++;
                }
                afterId = row.id();
            }
            if (page.size() < EMAIL_DIGEST_PAGE_SIZE) {
                break;
            }
        }
        return stale;
    }

    private record EmailDigestRow(UUID id, String email, String emailHash) {}
}
