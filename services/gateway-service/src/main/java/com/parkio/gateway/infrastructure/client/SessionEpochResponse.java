package com.parkio.gateway.infrastructure.client;

/**
 * Body of auth-service's internal session-epoch endpoint
 * ({@code GET /internal/auth/users/{userId}/session-epoch}). Only the current epoch is
 * used by the edge revocation check; {@code userId} is echoed for clarity.
 */
public record SessionEpochResponse(String userId, long sessionEpoch) {
}
