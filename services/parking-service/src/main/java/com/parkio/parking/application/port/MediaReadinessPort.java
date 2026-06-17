package com.parkio.parking.application.port;

import java.util.UUID;

/**
 * Port for confirming that a referenced media object has passed media-service's
 * safety checks (notably the malware scan) and is {@code READY} before a spot is
 * allowed to reference it.
 *
 * <p>Fail-closed contract, mirroring {@link MediaAccessPort}: a media-service outage
 * or any non-ready/unknown media must surface as a
 * {@link com.parkio.parking.domain.exception.ParkingException}, never as a leaked
 * transport error and never silently as "ready".
 */
public interface MediaReadinessPort {

    /**
     * Ensures {@code mediaId} exists and is {@code READY}. Throws
     * {@code MEDIA_NOT_READY} (422) when the media is missing or not yet servable, and
     * {@code MEDIA_ACCESS_UNAVAILABLE} (503) when media-service cannot be reached.
     */
    void ensureMediaReady(UUID mediaId);
}
