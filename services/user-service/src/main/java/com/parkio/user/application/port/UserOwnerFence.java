package com.parkio.user.application.port;

import java.util.UUID;

/** Coordinates user writes with account erasure (PA30-S01). */
public interface UserOwnerFence {

    /**
     * Joins the shared side of the fence for the current transaction and reports whether the
     * user may still be served ({@code false} once an erasure has committed its tombstone). A
     * wait behind an erasure in progress is bounded: when the bound runs out it throws
     * {@link com.parkio.user.domain.exception.UserException} with
     * {@link com.parkio.user.domain.exception.UserErrorCode#PROFILE_BUSY}.
     */
    boolean admitWrite(UUID userId);

    /** Takes the exclusive side for the erasure's transaction. This wait is not bounded. */
    void holdForErasure(UUID userId);
}
