package com.parkio.auth.application.durable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A database identity, {@code postgresql:<system_identifier>:<datname>}, as auth-service reads it
 * from {@code pg_control_system()}. {@code system_identifier} names the cluster lineage: a PITR or
 * physical clone keeps it, and another database on the same server shares it. So a recovery target
 * is refused when its cluster is the production identity's, whatever the database name; anything
 * that does not parse strictly is ambiguous and refused too. The same rule as
 * {@code check_target_identity} in {@code scripts/lib/recovery_evidence_bundle.py}; the shared
 * fixture {@code durable-erasure-evidence/v2/database-identities.json} pins both.
 *
 * @param systemIdentifier the cluster's {@code system_identifier}, printed without leading zeros
 * @param database the database name (no {@code ':'} or whitespace)
 */
public record DatabaseIdentity(String systemIdentifier, String database) {

    private static final Pattern FORMAT = Pattern.compile("postgresql:([1-9][0-9]{0,19}):([^:\\s]+)");

    public static DatabaseIdentity parse(String identity) {
        Matcher matcher = identity == null ? null : FORMAT.matcher(identity);
        if (matcher == null || !matcher.matches()) {
            throw new DurableEvidenceException("ambiguous database identity");
        }
        return new DatabaseIdentity(matcher.group(1), matcher.group(2));
    }

    /** Refuses {@code targetIdentity} when it is ambiguous or on the production identity's cluster. */
    public static void checkTarget(String targetIdentity, String productionIdentity) {
        DatabaseIdentity target = parse(targetIdentity);
        DatabaseIdentity production = parse(productionIdentity);
        if (target.systemIdentifier().equals(production.systemIdentifier())) {
            throw new DurableEvidenceException(
                    "target identity is on the cluster of the production identity pinned in the trust document");
        }
    }
}
