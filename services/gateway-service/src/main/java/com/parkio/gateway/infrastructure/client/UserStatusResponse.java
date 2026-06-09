package com.parkio.gateway.infrastructure.client;

/**
 * Local copy of user-service's internal status response (no shared module —
 * ai-context/01). Only the fields the gateway needs are bound; unknown fields are
 * ignored by Jackson's default lenient deserialization.
 */
public record UserStatusResponse(String userId, String status) {
}
