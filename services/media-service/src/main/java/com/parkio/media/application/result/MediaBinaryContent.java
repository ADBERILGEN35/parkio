package com.parkio.media.application.result;

import java.util.UUID;

/**
 * Raw bytes of a READY media object plus its stored content type, served only to
 * trusted internal callers (ai-validation-service vision analysis). Never exposed
 * on the public API surface; the bytes themselves must never be logged.
 */
public record MediaBinaryContent(UUID mediaId, String contentType, byte[] content) {
}
