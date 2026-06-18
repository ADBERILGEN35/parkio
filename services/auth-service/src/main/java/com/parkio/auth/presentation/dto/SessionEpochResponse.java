package com.parkio.auth.presentation.dto;

import java.util.UUID;

/**
 * Internal response for the gateway's per-request access-token revocation check.
 * Exposes only the {@code userId} and the current {@code sessionEpoch} — no
 * credentials or profile data. Not part of the public {@code /api/v1} surface.
 */
public record SessionEpochResponse(UUID userId, long sessionEpoch) {
}
