package com.parkio.user.application.port;

import java.util.UUID;

/** Coordinates user writes with account erasure (PA30-S01). */
public interface UserOwnerFence {

    boolean admitWrite(UUID userId);

    void holdForErasure(UUID userId);
}
