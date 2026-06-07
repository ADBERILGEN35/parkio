package com.parkio.media.presentation;

import com.parkio.media.domain.exception.MediaErrorCode;
import com.parkio.media.domain.exception.MediaException;
import com.parkio.media.presentation.dto.ApiError;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Maps domain {@link MediaException}s, multipart/validation failures and
 * infrastructure errors to consistent API error bodies (ai-context/04). HTTP status
 * lives here — the domain stays HTTP-free. Internal details are never leaked.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MediaException.class)
    public ResponseEntity<ApiError> handleMedia(MediaException ex) {
        MediaErrorCode code = ex.errorCode();
        HttpStatus status = statusFor(code);
        ApiError body = ApiError.of(code.name(), ex.getMessage(), clock.instant());
        return ResponseEntity.status(status).body(body);
    }

    /** Missing multipart {@code file} part or a required query/form parameter. */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleMissingPart(Exception ex) {
        ApiError body = ApiError.of("MALFORMED_REQUEST", "Required upload part 'file' is missing.", clock.instant());
        return ResponseEntity.badRequest().body(body);
    }

    /** Container-level upload size cap exceeded (belt-and-suspenders with the app check). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxSize(MaxUploadSizeExceededException ex) {
        ApiError body = ApiError.of("FILE_TOO_LARGE", "Uploaded file exceeds the maximum size.", clock.instant());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    /** Database constraint violation (e.g. a duplicate-checksum race). */
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

    private static HttpStatus statusFor(MediaErrorCode code) {
        return switch (code) {
            case MEDIA_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_MEDIA -> HttpStatus.CONFLICT;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case EMPTY_FILE -> HttpStatus.BAD_REQUEST;
            case MISSING_USER_ID -> HttpStatus.UNAUTHORIZED;
            case NOT_MEDIA_OWNER -> HttpStatus.FORBIDDEN;
        };
    }
}
