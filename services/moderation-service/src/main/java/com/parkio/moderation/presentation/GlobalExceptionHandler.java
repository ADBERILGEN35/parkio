package com.parkio.moderation.presentation;

import com.parkio.moderation.domain.exception.ModerationErrorCode;
import com.parkio.moderation.domain.exception.ModerationException;
import com.parkio.moderation.presentation.dto.ApiError;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain {@link ModerationException}s, validation failures and infrastructure
 * errors to consistent API error bodies (ai-context/04). HTTP status lives here —
 * the domain stays HTTP-free. Internal details are never leaked to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ModerationException.class)
    public ResponseEntity<ApiError> handleModeration(ModerationException ex) {
        ModerationErrorCode code = ex.errorCode();
        HttpStatus status = statusFor(code);
        ApiError body = ApiError.of(code.name(), ex.getMessage(), clock.instant());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        ApiError body = ApiError.of("VALIDATION_FAILED", "Request validation failed.", fieldErrors, clock.instant());
        return ResponseEntity.badRequest().body(body);
    }

    /** Unreadable / malformed request body (e.g. invalid JSON, bad enum value). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        ApiError body = ApiError.of("MALFORMED_REQUEST", "Request body is malformed or unreadable.", clock.instant());
        return ResponseEntity.badRequest().body(body);
    }

    /** Concurrent modification of the same case/appeal — surface as a conflict. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        ApiError body = ApiError.of("CONFLICT", "The resource was modified concurrently; please retry.", clock.instant());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Database constraint violation (e.g. a duplicate-report race). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        ApiError body = ApiError.of("CONFLICT", "The request conflicts with existing data.", clock.instant());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Catch-all: anything unmapped becomes a consistent 500 with no leaked detail. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unexpected error handling request", ex);
        ApiError body = ApiError.of("INTERNAL_ERROR", "An unexpected error occurred.", clock.instant());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static HttpStatus statusFor(ModerationErrorCode code) {
        return switch (code) {
            case MISSING_USER_ID -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CASE_NOT_FOUND, APPEAL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_REPORT, DUPLICATE_APPEAL, CASE_NOT_RESOLVED,
                 INVALID_CASE_STATE, INVALID_APPEAL_STATE -> HttpStatus.CONFLICT;
        };
    }
}
