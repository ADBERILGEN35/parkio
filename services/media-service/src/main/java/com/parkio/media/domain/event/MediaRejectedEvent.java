package com.parkio.media.domain.event;

import com.parkio.media.domain.MediaValidationType;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when an upload is rejected (failed validation or duplicate). No media row
 * is persisted for a rejection, so {@code mediaId} is a generated correlation id.
 * Carries the failing {@link MediaValidationType} and a short reason.
 */
public record MediaRejectedEvent(
        UUID eventId,
        UUID mediaId,
        UUID ownerUserId,
        MediaValidationType validationType,
        String reason,
        String checksum,
        Instant occurredAt) implements MediaEvent {

    public static final String TYPE = "MediaRejected";

    public static MediaRejectedEvent of(UUID ownerUserId,
                                        MediaValidationType validationType,
                                        String reason,
                                        String checksum,
                                        Instant occurredAt) {
        return new MediaRejectedEvent(UUID.randomUUID(), UUID.randomUUID(), ownerUserId,
                validationType, reason, checksum, occurredAt);
    }

    @Override
    public UUID aggregateId() {
        return mediaId;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
