package com.parkio.media.domain;

/**
 * What was validated. {@code FILE_SIZE}, {@code MIME_TYPE}, {@code DUPLICATE} and
 * {@code MALWARE_SCAN} are checked synchronously at upload and are blocking (a failed
 * check rejects the upload); {@code IMAGE_SAFETY} and {@code PARKING_RELEVANCE} are
 * advisory checks performed later by other services (not implemented here).
 *
 * <p>{@code MALWARE_SCAN} is a basic anti-malware check (ClamAV) — NOT illegal/abusive
 * content classification, which still requires a dedicated provider and/or human
 * moderation (ai-context/07).
 */
public enum MediaValidationType {
    FILE_SIZE,
    MIME_TYPE,
    DUPLICATE,
    MALWARE_SCAN,
    IMAGE_SAFETY,
    PARKING_RELEVANCE
}
