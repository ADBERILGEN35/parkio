package com.parkio.gateway.shared;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Consistent edge error body (ai-context/04). {@code traceId} carries the request
 * correlation id so a client can quote it when reporting a problem. No stack
 * traces or internal details are ever exposed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String traceId, Instant timestamp) {
}
