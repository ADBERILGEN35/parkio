package com.parkio.parking.externalsource;

/** Bounded municipal-source operational SLA state (distinct from occupancy freshness). */
public enum MunicipalSourceOperationalState {
    DISABLED,
    NEVER_RUN,
    HEALTHY,
    DEGRADED,
    CRITICAL,
    RECOVERING,
    STALE_OPERATION,
    UNKNOWN
}
