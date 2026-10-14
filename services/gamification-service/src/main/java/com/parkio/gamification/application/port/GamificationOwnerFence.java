package com.parkio.gamification.application.port;

import java.util.UUID;

/**
 * Database-backed coordination between gamification writes for one user and that user's
 * account erasure (N-07 / #357). A write joins the user's fence in shared mode for the rest
 * of its transaction; erasure takes it exclusively. A write that joins after the erasure
 * tombstone committed is refused so late events cannot recreate user-scoped data.
 */
public interface GamificationOwnerFence {

    /**
     * Joins the user's fence in shared mode until the current transaction ends, then reports
     * whether the user may still receive writes ({@code false} once the erasure tombstone exists).
     * Must be called inside a transaction, before user-scoped rows or outbox events are written.
     */
    boolean admitWrite(UUID userId);

    /**
     * Takes the user's fence exclusively until the current transaction ends, waiting for every
     * write that already joined it. Must be called inside a transaction before erase statements.
     */
    void holdForErasure(UUID userId);
}
