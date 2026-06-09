package com.parkio.parking.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Port for obtaining a short-lived signed access URL for a media object from
 * media-service. Used only after parking has authorized the requester against
 * spot-visibility rules — the media side trusts this caller and performs no
 * ownership check of its own.
 *
 * <p>Implementations must fail safely: a media-service outage surfaces as a
 * {@link com.parkio.parking.domain.exception.ParkingException} with
 * {@code MEDIA_ACCESS_UNAVAILABLE} (mapped to 503), never as a leaked transport
 * error.
 */
public interface MediaAccessPort {

    /**
     * Requests a signed GET URL for {@code mediaId}. {@code requesterUserId} is
     * forwarded for audit logging only.
     */
    MediaAccessGrant requestAccessUrl(UUID mediaId, UUID requesterUserId);

    /** Signed access grant: the URL is short-lived and never persisted. */
    record MediaAccessGrant(UUID mediaId, String accessUrl, Instant expiresAt) {
    }
}
