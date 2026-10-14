package com.parkio.notification.application.port;

import java.util.UUID;

/** Coordinates notification writes with account erasure (N-07 / #359). */
public interface NotificationOwnerFence {

    boolean admitWrite(UUID userId);

    void holdForErasure(UUID userId);
}
