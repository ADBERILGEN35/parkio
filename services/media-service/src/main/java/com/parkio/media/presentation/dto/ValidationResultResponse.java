package com.parkio.media.presentation.dto;

import com.parkio.media.domain.MediaValidationResult;
import java.time.Instant;

/** A single validation outcome for a media file. */
public record ValidationResultResponse(
        String validationType,
        String result,
        String message,
        Instant createdAt) {

    public static ValidationResultResponse from(MediaValidationResult r) {
        return new ValidationResultResponse(r.validationType().name(), r.result().name(),
                r.message(), r.createdAt());
    }
}
