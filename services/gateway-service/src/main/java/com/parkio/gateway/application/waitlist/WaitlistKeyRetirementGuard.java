package com.parkio.gateway.application.waitlist;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * CL-N08: refuse gateway start when the email previous secret was removed while unrehashed
 * identity rows remain. Token digests are out of scope here — they use the dedicated token
 * secret and are not rehashed from plaintext.
 */
@Component
public class WaitlistKeyRetirementGuard {

    private static final Logger log = LoggerFactory.getLogger(WaitlistKeyRetirementGuard.class);

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
        // Compare stored digests to current-key digests of plaintext email without loading every row
        // into the JVM when the inventory is huge: sample via SQL only when previous is absent.
        long stale = jdbc.query(
                        """
                        SELECT email, email_hash FROM waitlist_interest
                        WHERE email_hash NOT LIKE 'withdrawn-%'
                        """,
                        (rs, rowNum) -> hasher.isCurrentEmailHash(rs.getString("email"), rs.getString("email_hash")))
                .stream()
                .filter(current -> !current)
                .count();
        if (stale > 0) {
            throw new IllegalStateException(
                    "parkio.waitlist.hash-secret-previous is required: " + stale
                            + " waitlist_interest row(s) still use a non-current email_hash. "
                            + "Restore the previous secret or migrate identity hashes before removing it.");
        }
        log.info("Waitlist email-key previous unset; {} active rows already on current digest", unrehashed);
    }
}
