/**
 * Client-side mirror of the media-service upload constraints
 * (`services/media-service` → `application.yml` → `parkio.media`). We enforce
 * these on-device BEFORE uploading so a capture that the backend would reject
 * (415/413/422) never leaves the phone, and so we know what to compress toward.
 *
 * Keep these in sync with the backend if the server limits ever change.
 */

/** Content types the backend accepts. We always prepare to JPEG, the safest. */
export const ALLOWED_CONTENT_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;
export type AllowedContentType = (typeof ALLOWED_CONTENT_TYPES)[number];

/** Hard upper bound enforced by the backend (`parkio.media.max-file-size: 10MB`). */
export const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

/** Backend pixel ceilings (`max-image-width/height/pixels`). */
export const MAX_IMAGE_DIMENSION = 8000;
export const MAX_IMAGE_PIXELS = 40_000_000;

/**
 * Preparation targets. We resize the longest edge down to this so a modern
 * phone photo (often 12–48 MP) lands comfortably under every backend ceiling
 * while staying sharp enough to assess a parking spot. JPEG quality starts here
 * and steps down only if the encoded file still exceeds {@link MAX_FILE_SIZE_BYTES}.
 */
export const PREPARE_MAX_EDGE = 2048;
export const PREPARE_INITIAL_QUALITY = 0.7;
export const PREPARE_MIN_QUALITY = 0.4;
export const PREPARE_QUALITY_STEP = 0.15;

/** Output content type after preparation. */
export const PREPARED_CONTENT_TYPE: AllowedContentType = 'image/jpeg';
export const PREPARED_FILE_EXTENSION = 'jpg';
