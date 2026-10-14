package com.parkio.analytics.application.port;

import java.util.UUID;

/**
 * Coordinates analytics writes for one user with that user's account erasure (N-07 / #358).
 * Writes join shared; erasure takes exclusive. Post-tombstone writes are refused.
 */
public interface AnalyticsOwnerFence {

    boolean admitWrite(UUID userId);

    void holdForErasure(UUID userId);
}
