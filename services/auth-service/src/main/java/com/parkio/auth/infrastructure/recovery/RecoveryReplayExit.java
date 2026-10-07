package com.parkio.auth.infrastructure.recovery;

/**
 * Exit codes of the recovery-replay command (docs/architecture/erasure-restore-replay-contract.md,
 * Recovery-replay command). {@code 0} only for {@link #COMPLETE}. A Spring startup failure before the
 * command runs is reported as {@link #INTERNAL} by {@link RecoveryReplayLaunch}.
 */
public enum RecoveryReplayExit {

    /** Every participant the attempt requires, auth included, acknowledged every user. */
    COMPLETE(0),
    /** Disabled or refused before anything was read: profile, flag, durable writer or arguments. */
    REFUSED(20),
    /** The evidence, the trusted-set file or the backup anchor does not verify. */
    INVALID_EVIDENCE(21),
    /** The connected database is the production identity, does not match the ticket, or is unreadable. */
    TARGET_REFUSED(22),
    /** The attempt or dataset does not match the trusted-set file or an attempt already recorded. */
    ATTEMPT_MISMATCH(23),
    /** A participant reported FAILED: the attempt is BLOCKED. */
    BLOCKED(24),
    /** The bounded wait ended before every acknowledgement arrived: the attempt is still BLOCKED. */
    TIMEOUT(25),
    /** An unexpected failure. */
    INTERNAL(26);

    private final int code;

    RecoveryReplayExit(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
