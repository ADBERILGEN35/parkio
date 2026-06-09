package com.parkio.parking.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.slf4j.MDC;

/**
 * Consistent API error body (ai-context/04). {@code fieldErrors} is present only
 * for validation failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        String traceId,
        List<FieldError> fieldErrors,
        Instant timestamp) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(String code, String message, Instant timestamp) {
        return new ApiError(code, message, currentTraceId(), null, timestamp);
    }

    public static ApiError of(String code, String message, List<FieldError> fieldErrors, Instant timestamp) {
        return new ApiError(code, message, currentTraceId(), fieldErrors, timestamp);
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? "unknown" : traceId;
    }
}
