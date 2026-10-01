package com.parkio.parking.trust;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/**
 * Canonical ledger order shared with PostgreSQL {@code ORDER BY evaluated_at, id}.
 * PostgreSQL compares UUID bytes unsigned. {@link UUID#compareTo} compares the two
 * longs as signed, so ids on opposite sides of the high bit sort the other way.
 */
public final class TrustLedgerOrder {

    public static final Comparator<UUID> UNSIGNED_ID = (left, right) -> {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0
                ? high
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };

    private TrustLedgerOrder() {}

    public static int compare(Instant leftAt, UUID leftId, Instant rightAt, UUID rightId) {
        int byTime = leftAt.compareTo(rightAt);
        return byTime != 0 ? byTime : UNSIGNED_ID.compare(leftId, rightId);
    }
}
