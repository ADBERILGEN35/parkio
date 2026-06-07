package com.parkio.parking.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Consistent API error body (ai-context/04). {@code fieldErrors} is present only
 * for validation failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        List<FieldError> fieldErrors,
        Instant timestamp) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(String code, String message, Instant timestamp) {
        return new ApiError(code, message, null, timestamp);
    }

    public static ApiError of(String code, String message, List<FieldError> fieldErrors, Instant timestamp) {
        return new ApiError(code, message, fieldErrors, timestamp);
    }
}
