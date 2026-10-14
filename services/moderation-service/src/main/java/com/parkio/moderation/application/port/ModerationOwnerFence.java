package com.parkio.moderation.application.port;

import java.util.UUID;

/** Coordinates moderation writes with account erasure (N-07 / #360). */
public interface ModerationOwnerFence {

    boolean admitWrite(UUID userId);

    void holdForErasure(UUID userId);
}
