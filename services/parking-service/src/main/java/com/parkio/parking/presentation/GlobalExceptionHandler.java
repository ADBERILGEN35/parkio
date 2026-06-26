package com.parkio.parking.presentation;

import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.idempotency.IdempotencyException;
import com.parkio.platform.api.ApiError;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps domain {@link ParkingException}s, validation failures and infrastructure
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

    @ExceptionHandler(ParkingException.class)
    public ResponseEntity<ApiError> handleParking(ParkingException ex) {
        ParkingErrorCode code = ex.errorCode();
        HttpStatus status = statusFor(code);
        ApiError body = ApiError.of(code.name(), ex.getMessage(), clock.instant());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IdempotencyException.class)
    public ResponseEntity<ApiError> handleIdempotency(IdempotencyException ex) {
        HttpStatus status = "IDEMPOTENCY_KEY_CONFLICT".equals(ex.code())
                        || "IDEMPOTENCY_REQUEST_IN_PROGRESS".equals(ex.code())
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(ApiError.of(ex.code(), ex.getMessage(), clock.instant()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        ApiError body = ApiError.of("VALIDATION_FAILED", "Request validation failed.", fieldErrors, clock.instant());
        return ResponseEntity.badRequest().body(body);
    }

    /** Missing/!typed query params (e.g. nearby without lat/lng) and unreadable bodies. */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        ApiError body = ApiError.of("MALFORMED_REQUEST", "Request is malformed or missing parameters.", clock.instant());
        return ResponseEntity.badRequest().body(body);
    }

    /** Domain invariant breached at the boundary (e.g. out-of-range coordinates). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        ApiError body = ApiError.of("INVALID_REQUEST", ex.getMessage(), clock.instant());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Concurrency conflicts: a duplicate-verification race ({@link DataIntegrityViolationException})
     * or a lost update on a contended spot ({@link ObjectOptimisticLockingFailureException}).
     * Both map to 409 with a generic message — no internal detail leaked.
     */
    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleConflict(Exception ex) {
        log.warn("Concurrency/integrity conflict: {}", ex.getClass().getSimpleName());
        ApiError body = ApiError.of("CONFLICT", "The request conflicts with the current state of the resource.",
                clock.instant());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Catch-all: anything unmapped becomes a consistent 500 with no leaked detail. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unexpected error handling request", ex);
        ApiError body = ApiError.of("INTERNAL_ERROR", "An unexpected error occurred.", clock.instant());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static HttpStatus statusFor(ParkingErrorCode code) {
        return switch (code) {
            case SPOT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ILLEGAL_SPOT_REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case OWNER_CANNOT_VERIFY, OWNER_CANNOT_CLAIM -> HttpStatus.FORBIDDEN;
            case ALREADY_VERIFIED, SPOT_NOT_VERIFIABLE, SPOT_NOT_CLAIMABLE, SPOT_EXPIRED -> HttpStatus.CONFLICT;
            case MISSING_USER_ID -> HttpStatus.UNAUTHORIZED;
            case MEDIA_ACCESS_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case MEDIA_NOT_READY -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
