package com.parkio.parking.trust;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Deterministic ledger identifiers derived from evidence identity. */
public final class TrustLedgerIds {

    private TrustLedgerIds() {}

    public static UUID ledgerEntryId(UUID evidenceId) {
        return named("trust-ledger|" + evidenceId);
    }

    public static UUID evaluationId(UUID evidenceId) {
        return named("trust-evaluation|" + evidenceId);
    }

    private static UUID named(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
