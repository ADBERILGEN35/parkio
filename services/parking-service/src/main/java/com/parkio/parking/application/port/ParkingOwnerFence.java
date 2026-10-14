package com.parkio.parking.application.port;

import java.util.UUID;

/** Coordinates parking writes with account erasure (N-07 / #362). */
public interface ParkingOwnerFence {

    boolean admitWrite(UUID userId);

    void holdForErasure(UUID userId);
}
