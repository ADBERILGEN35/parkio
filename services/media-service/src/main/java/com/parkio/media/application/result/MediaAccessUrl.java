package com.parkio.media.application.result;

import java.time.Instant;
import java.util.UUID;

/**
 * A freshly generated, short-lived presigned GET URL for a media object. Never
 * persisted; carries no bucket/object-key fields beyond what the URL itself needs.
 */
public record MediaAccessUrl(UUID mediaId, String url, Instant expiresAt) {
}
