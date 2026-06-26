package com.parkio.gamification.presentation;

import com.parkio.gamification.domain.exception.GamificationErrorCode;
import com.parkio.gamification.domain.exception.GamificationException;
import com.parkio.platform.api.ApiError;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps domain {@link GamificationException}s, request errors and infrastructure
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

    @ExceptionHandler(GamificationException.class)
    public ResponseEntity<ApiError> handleGamification(GamificationException ex) {
        GamificationErrorCode code = ex.errorCode();
        HttpStatus status = statusFor(code);
        ApiError body = ApiError.of(code.name(), ex.getMessage(), clock.instant());
        return ResponseEntity.status(status).body(body);
    }

    /** Invalid/missing query params (e.g. a non-numeric leaderboard limit). */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadParam(Exception ex) {
        ApiError body = ApiError.of("MALFORMED_REQUEST", "Request parameters are malformed or missing.", clock.instant());
        return ResponseEntity.badRequest().body(body);
    }

    /** Domain invariant breached at the boundary (e.g. out-of-range limit). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        ApiError body = ApiError.of("INVALID_REQUEST", ex.getMessage(), clock.instant());
        return ResponseEntity.badRequest().body(body);
    }

    /** Database constraint violation (e.g. a duplicate idempotency-key race). */
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

    private static HttpStatus statusFor(GamificationErrorCode code) {
        return switch (code) {
            case MISSING_USER_ID -> HttpStatus.UNAUTHORIZED;
            case RULE_NOT_CONFIGURED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
