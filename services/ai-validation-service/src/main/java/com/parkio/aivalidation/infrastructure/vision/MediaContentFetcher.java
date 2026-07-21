package com.parkio.aivalidation.infrastructure.vision;

import java.util.UUID;

/**
 * Fetches the actual bytes of a media object for vision analysis. Backed by
 * media-service's internal, gateway-secret-protected content endpoint — private
 * media never gets a public URL for this path.
 */
public interface MediaContentFetcher {

    /**
     * Returns the image bytes and stored content type for {@code mediaId}.
     *
     * @throws MediaContentException when the media is missing, oversized, of an
     *         unsupported type, or temporarily unavailable (callers fail closed)
     */
    MediaContent fetch(UUID mediaId);

    /**
     * Image bytes plus the content type media-service stored for them, and the
     * optional uploader-claimed parking region.
     */
    record MediaContent(byte[] bytes, String contentType, ClaimedRegion claimedRegion) {
        public MediaContent(byte[] bytes, String contentType) {
            this(bytes, contentType, null);
        }
    }
}
