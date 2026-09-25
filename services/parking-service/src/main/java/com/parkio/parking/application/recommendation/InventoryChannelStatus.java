package com.parkio.parking.application.recommendation;

/** Per-inventory outcome for a recommendation response. */
public enum InventoryChannelStatus {
    /** Channel queried successfully and returned at least one candidate. */
    AVAILABLE,
    /** Channel queried successfully but returned no candidates. */
    EMPTY,
    /** Channel was requested but failed; other inventory may still be present. */
    DEGRADED,
    /** Channel intentionally excluded by request or operational policy. */
    DISABLED
}
