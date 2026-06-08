package com.parkio.aivalidation.presentation;

import com.parkio.aivalidation.domain.exception.AiValidationErrorCode;
import com.parkio.aivalidation.domain.exception.AiValidationException;
import com.parkio.aivalidation.presentation.dto.ApiError;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain {@link AiValidationException}s, validation failures and infrastructure
 * errors to consistent API error bodies (ai-context/04). HTTP status lives here — the
 * domain stays HTTP-free. Internal details are never leaked to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(AiValidationException.class)
    public ResponseEntity<ApiError> handleAiValidation(AiValidationException ex) {
        AiValidationErrorCode code = ex.errorCode();
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

    /** Unreadable / malformed request body (e.g. invalid JSON, bad UUID). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        ApiError body = ApiError.of("MALFORMED_REQUEST", "Request body is malformed or unreadable.", clock.instant());
        return ResponseEntity.badRequest().body(body);
    }

    /** Database constraint violation (e.g. a score check). */
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

    private static HttpStatus statusFor(AiValidationErrorCode code) {
        return switch (code) {
            case MISSING_USER_ID -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case VALIDATION_RESULT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_SCORE -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
