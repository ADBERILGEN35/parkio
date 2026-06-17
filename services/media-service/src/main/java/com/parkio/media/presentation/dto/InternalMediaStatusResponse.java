package com.parkio.media.presentation.dto;

import com.parkio.media.domain.MediaStatus;
import java.util.UUID;

/**
 * Lifecycle status of a media object, returned to a trusted internal caller
 * (parking-service) so it can require {@code READY} before letting a spot reference
 * the media. Carries no storage internals.
 */
public record InternalMediaStatusResponse(UUID mediaId, String status) {

    public static InternalMediaStatusResponse of(UUID mediaId, MediaStatus status) {
        return new InternalMediaStatusResponse(mediaId, status.name());
    }
}
