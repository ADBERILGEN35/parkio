package com.parkio.media.domain;

/**
 * What was validated. {@code FILE_SIZE}, {@code MIME_TYPE} and {@code DUPLICATE} are
 * checked synchronously at upload; {@code IMAGE_SAFETY} and {@code PARKING_RELEVANCE}
 * are advisory checks performed later by other services (not implemented here).
 */
public enum MediaValidationType {
    FILE_SIZE,
    MIME_TYPE,
    DUPLICATE,
    IMAGE_SAFETY,
    PARKING_RELEVANCE
}
