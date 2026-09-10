package com.parkio.user.domain.place;

/**
 * Kind of parking target a user may record as recently used (WP-SPA-07 v1).
 *
 * <p>Only municipal facilities are supported. Community spots are deferred until
 * spot lifecycle identity is stable enough for durable history references.
 */
public enum RecentParkingTargetKind {
    MUNICIPAL_FACILITY
}
