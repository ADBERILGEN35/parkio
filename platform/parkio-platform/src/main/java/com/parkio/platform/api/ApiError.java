package com.parkio.platform.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.parkio.platform.http.PlatformHeaders;
import java.time.Instant;
import java.util.List;
import org.slf4j.MDC;

/**
 * Service-agnostic API error envelope.
 *
 * <p>This type is infrastructure-only. Services still own exception mapping and
 * status-code decisions.
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
        String traceId = MDC.get(PlatformHeaders.MDC_CORRELATION_ID);
        return traceId == null || traceId.isBlank() ? "unknown" : traceId;
    }
}
