package com.parkio.aivalidation.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of media-service's {@code MediaUploaded} payload (event-contracts.md).
 * Only the fields this service needs are mirrored; unknown fields are ignored
 * (contracts are duplicated, never shared — ai-context/01).
 */
public record MediaUploadedEvent(
        UUID eventId,
        UUID mediaId,
        UUID ownerUserId,
        String contentType,
        long fileSize,
        String checksum,
        Instant occurredAt) {
}
