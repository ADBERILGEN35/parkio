package com.parkio.parking.domain;

/**
 * Controlled product rejection reason codes. Prefer resolving user-facing text from
 * {@link RejectionReasonCatalog} rather than free-form model output.
 */
public enum RejectionReasonCode {
    LEGACY_POLICY_RESET,
    CLEARLY_UNRELATED_CONTENT,
    INDOOR_SCENE,
    SELFIE_OR_PERSONAL_PHOTO,
    FOOD_OR_RANDOM_OBJECT,
    SCREENSHOT_OR_DOCUMENT,
    NO_ROAD_OR_PARKING_CONTEXT,
    UNUSABLE_IMAGE,
    IMAGE_TOO_DARK,
    IMAGE_TOO_BLURRY,
    IMAGE_CORRUPTED,
    LEGALITY_CONCERN,
    DUPLICATE_SUBMISSION,
    MANUAL_MODERATOR_REJECTION,
    OTHER
}
