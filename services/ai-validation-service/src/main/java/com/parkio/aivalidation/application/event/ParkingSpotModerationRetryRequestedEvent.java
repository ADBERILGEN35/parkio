package com.parkio.aivalidation.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service's {@code ParkingSpotModerationRetryRequested} payload
 * (event-contracts.md). Emitted when a spot's publication gate did not answer within its
 * deadline — typically because the original request or its result was lost to a
 * dead-letter — and parking-service is asking for the validation to be re-run.
 *
 * <p>Handled exactly like a fresh creation: a distinct {@code eventId} means the inbox
 * treats it as new work, while the classifier's own conclusive-result reuse keeps a
 * genuinely re-runnable verdict cheap. Contracts are duplicated, never shared
 * (ai-context/01); unknown fields are ignored.
 */
public record ParkingSpotModerationRetryRequestedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID mediaId,
        int attempt,
        Instant deadlineAt,
        Instant occurredAt) {
}
