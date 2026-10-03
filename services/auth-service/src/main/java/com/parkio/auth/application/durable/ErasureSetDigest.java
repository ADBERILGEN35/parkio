package com.parkio.auth.application.durable;

import java.util.List;

/**
 * The digest that binds restore ACKs to one erasure set: the checkpoint ledger digest of the set
 * (one {@code {authUserId, erasedAt}} entry per user, ordered by {@code authUserId}; see
 * {@link DurableErasureEvidence#checkpoint}). The same set always yields the same digest, and a
 * set with two entries for one user is refused.
 */
public final class ErasureSetDigest {

    private ErasureSetDigest() {
    }

    public static String of(List<ErasureLedgerEntry> entries) {
        return DurableErasureEvidence.ledgerDigest(DurableErasureEvidence.ledgerEntries(entries));
    }
}
