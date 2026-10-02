package com.parkio.media.application.port;

import java.util.UUID;

/**
 * Database-backed coordination between the media writes of one owner and that owner's account
 * erasure (U05). A write joins the owner's fence in shared mode for the rest of its transaction,
 * so it holds the fence from its admission check through its object write and its metadata
 * commit; the erasure takes the fence exclusively, so it waits for writes already admitted and
 * keeps new ones out until its own transaction ends. A write that joins after the owner's erasure
 * tombstone committed is refused.
 */
public interface MediaOwnerFence {

    /**
     * Joins the owner's fence in shared mode until the current transaction ends, then reports
     * whether the owner may still write ({@code false} once the owner's erasure tombstone exists).
     * Must be called inside a transaction, before anything is stored for the owner.
     */
    boolean admitWrite(UUID ownerUserId);

    /**
     * Takes the owner's fence exclusively until the current transaction ends, waiting for every
     * write that already joined it. Must be called inside a transaction, before its other
     * statements.
     */
    void holdForErasure(UUID ownerUserId);
}
