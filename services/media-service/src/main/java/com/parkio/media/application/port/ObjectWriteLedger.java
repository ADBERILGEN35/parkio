package com.parkio.media.application.port;

import java.util.UUID;

/**
 * Durable record of object writes whose effect is not yet accounted for (U05).
 *
 * <p>A PUT whose client fails after sending (a timeout, a broken connection, a 5xx reply) may
 * still be applied by the store later, and nothing bounds when: request authentication is only
 * checked when the store receives a request, not when it completes it. So an upload records its
 * PUT before sending it, in a transaction of its own that survives the upload's rollback or a
 * crash, and a media erasure does not report SUCCESS while any write of the user is recorded.
 */
public interface ObjectWriteLedger {

    /** Records, and commits, that a PUT of {@code objectKey} for the owner is about to be sent. */
    UUID recordPending(UUID ownerUserId, String objectKey);

    /** The store confirmed the PUT: its object exists and that request cannot apply again. Commits. */
    void markApplied(UUID writeId);

    /** Forgets the write in its own committed transaction (rejected, or its object confirmed gone). */
    void forgetNow(UUID writeId);

    /**
     * Forgets the write in the caller's transaction: the media row that commits with it accounts
     * for the object from then on.
     */
    void forgetWithCaller(UUID writeId);
}
