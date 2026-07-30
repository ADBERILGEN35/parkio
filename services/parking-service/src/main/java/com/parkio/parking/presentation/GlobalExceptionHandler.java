package com.parkio.parking.presentation;

import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.idempotency.IdempotencyException;
import com.parkio.platform.api.ApiError;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps domain {@link ParkingException}s, validation failures and infrastructure
 * errors to consistent API error bodies (ai-context/04). HTTP status lives here —
 * the domain stays HTTP-free. Internal details are never leaked to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Set<String> EXPECTED_CONFLICT_CONSTRAINTS = Set.of(
            "uq_parking_spot_verifications_spot_user",
            "uq_parking_sessions_active_user");

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

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex) {
        List<ApiError.FieldError> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiError.FieldError(
                                parameterName(result.getMethodParameter().getParameterName()),
                                error.getDefaultMessage())))
                .toList();
        ApiError body = ApiError.of(
                "VALIDATION_FAILED", "Request validation failed.", fieldErrors, clock.instant());
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
     * Controllers throw {@link ResponseStatusException} for gated admin checks and
     * feature conflicts. Preserve the declared status — never collapse to 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String reason = ex.getReason();
        String message = (reason == null || reason.isBlank())
                ? defaultMessageFor(status)
                : reason;
        return ResponseEntity.status(status)
                .body(ApiError.of(codeForStatus(status), message, clock.instant()));
    }

    /**
     * Missing handler / property-disabled controller (Spring MVC 6). Must stay 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        log.debug("No resource for {} {}", ex.getHttpMethod(), ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Resource not found.", clock.instant()));
    }

    /** Wrong HTTP verb on a known path. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiError.of("METHOD_NOT_ALLOWED", "HTTP method not allowed for this resource.",
                        clock.instant()));
    }

    /** A lost update on a contended spot is a public state conflict. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticConflict(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic locking conflict: {}", ex.getPersistentClassName());
        return conflictResponse();
    }

    /**
     * Only named constraints that encode public concurrency invariants are 409s.
     * Other integrity failures are unexpected server errors and must not be
     * misclassified or expose database details.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrityViolation(DataIntegrityViolationException ex) {
        String constraintName = constraintName(ex);
        if (constraintName != null && EXPECTED_CONFLICT_CONSTRAINTS.contains(constraintName)) {
            log.warn("Expected database constraint conflict: {}", constraintName);
            return conflictResponse();
        }
        log.error("Unexpected database integrity failure", ex);
        return internalErrorResponse();
    }

    /** Catch-all: only true unexpected failures become a consistent 500 with no leaked detail. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unexpected error handling request", ex);
        return internalErrorResponse();
    }

    private ResponseEntity<ApiError> conflictResponse() {
        ApiError body = ApiError.of("CONFLICT", "The request conflicts with the current state of the resource.",
                clock.instant());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private ResponseEntity<ApiError> internalErrorResponse() {
        ApiError body = ApiError.of("INTERNAL_ERROR", "An unexpected error occurred.", clock.instant());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static String constraintName(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation) {
                return constraintViolation.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }

    private static HttpStatus statusFor(ParkingErrorCode code) {
        return switch (code) {
            case SPOT_NOT_FOUND, PARKING_SESSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_PARKING_SESSION_CURSOR -> HttpStatus.BAD_REQUEST;
            case ILLEGAL_SPOT_REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case OWNER_CANNOT_VERIFY, OWNER_CANNOT_CLAIM -> HttpStatus.FORBIDDEN;
            case ALREADY_VERIFIED, SPOT_NOT_VERIFIABLE, SPOT_NOT_CLAIMABLE, SPOT_EXPIRED,
                    PARKING_SESSION_NOT_ACTIVE, PARKING_SESSION_NOT_TERMINAL,
                    ACTIVE_PARKING_SESSION_EXISTS -> HttpStatus.CONFLICT;
            case MISSING_USER_ID -> HttpStatus.UNAUTHORIZED;
            case MEDIA_ACCESS_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case MEDIA_NOT_READY -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private static String parameterName(String parameterName) {
        return parameterName == null ? "request" : parameterName;
    }

    private static String codeForStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            default -> status.is4xxClientError() ? "REQUEST_REJECTED" : "INTERNAL_ERROR";
        };
    }

    private static String defaultMessageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Request is malformed or invalid.";
            case 401 -> "Authentication is required.";
            case 403 -> "Request is forbidden.";
            case 404 -> "Resource not found.";
            case 405 -> "HTTP method not allowed for this resource.";
            case 409 -> "The request conflicts with the current state of the resource.";
            case 429 -> "Too many requests.";
            default -> status.is4xxClientError()
                    ? "Request was rejected."
                    : "An unexpected error occurred.";
        };
    }
}
