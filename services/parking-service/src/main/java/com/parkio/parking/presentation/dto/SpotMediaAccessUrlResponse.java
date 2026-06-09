package com.parkio.parking.presentation.dto;

import com.parkio.parking.application.result.SpotMediaAccess;
import java.time.Instant;
import java.util.UUID;

/**
 * Short-lived signed URL for a spot's photo. Only the signed URL and its expiry
 * are exposed — no bucket, object key or other storage internals. Clients
 * re-request the URL after {@code expiresAt}.
 */
public record SpotMediaAccessUrlResponse(UUID spotId, UUID mediaId, String accessUrl, Instant expiresAt) {

    public static SpotMediaAccessUrlResponse from(SpotMediaAccess access) {
        return new SpotMediaAccessUrlResponse(access.spotId(), access.mediaId(), access.accessUrl(),
                access.expiresAt());
    }
}
