package com.parkio.gateway.application.waitlist;

import java.time.Instant;
import java.util.UUID;

/**
 * Records operational (Slack) notifications for waitlist lifecycle events.
 *
 * <p>Implementations must be called inside the transaction that performs the
 * state change and must never throw: a notification problem may drop the
 * notification but must not fail or roll back the subscriber's request.
 */
public interface WaitlistOpsNotifier {

    /** A PENDING subscription transitioned to CONFIRMED (double opt-in completed). */
    void subscriptionConfirmed(UUID interestId, Instant confirmedAt);
}
