package com.parkio.media.presentation.dto;

import java.util.UUID;

/**
 * Optional audit context sent by trusted internal callers when requesting an
 * access URL (e.g. parking-service forwarding the end user's id and the purpose
 * {@code SPOT_PHOTO_VIEW}). Used for logging only — never for authorization.
 */
public record InternalAccessUrlRequest(UUID requesterUserId, String purpose) {
}
