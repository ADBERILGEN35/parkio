package com.parkio.parking.application.result;

import java.time.Instant;
import java.util.UUID;

/**
 * Short-lived signed access grant for the photo of a parking spot the requester
 * is allowed to see. Generated per request and never persisted; clients re-fetch
 * once {@code expiresAt} passes.
 */
public record SpotMediaAccess(UUID spotId, UUID mediaId, String accessUrl, Instant expiresAt) {
}
