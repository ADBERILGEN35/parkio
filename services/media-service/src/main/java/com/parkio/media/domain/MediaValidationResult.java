package com.parkio.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single validation outcome recorded against a media file (append-only). Pure
 * domain value with no framework dependencies.
 */
public final class MediaValidationResult {

    private final UUID id;
    private final UUID mediaId;
    private final MediaValidationType validationType;
    private final MediaValidationOutcome result;
    private final String message;
    private final Instant createdAt;

    public MediaValidationResult(UUID id,
                                 UUID mediaId,
                                 MediaValidationType validationType,
                                 MediaValidationOutcome result,
                                 String message,
                                 Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.mediaId = Objects.requireNonNull(mediaId, "mediaId");
        this.validationType = Objects.requireNonNull(validationType, "validationType");
        this.result = Objects.requireNonNull(result, "result");
        this.message = message;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static MediaValidationResult of(UUID mediaId,
                                           MediaValidationType validationType,
                                           MediaValidationOutcome result,
                                           String message,
                                           Instant now) {
        return new MediaValidationResult(UUID.randomUUID(), mediaId, validationType, result, message, now);
    }

    public UUID id() {
        return id;
    }

    public UUID mediaId() {
        return mediaId;
    }

    public MediaValidationType validationType() {
        return validationType;
    }

    public MediaValidationOutcome result() {
        return result;
    }

    public String message() {
        return message;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
