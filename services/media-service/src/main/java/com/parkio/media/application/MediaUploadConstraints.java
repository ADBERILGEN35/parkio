package com.parkio.media.application;

import java.util.Set;

/**
 * Configurable upload limits, supplied by infrastructure from
 * {@code parkio.media.*}. Kept as a plain value in the application layer so the
 * service has no dependency on Spring configuration types.
 */
public record MediaUploadConstraints(
        Set<String> allowedContentTypes,
        long maxFileSizeBytes,
        int maxImageWidth,
        int maxImageHeight,
        long maxImagePixels) {

    public boolean allows(String contentType) {
        return contentType != null && allowedContentTypes.contains(contentType);
    }

    public boolean exceedsMaxSize(long size) {
        return size > maxFileSizeBytes;
    }

    public boolean exceedsImageLimits(int width, int height) {
        if (width <= 0 || height <= 0) {
            return true;
        }
        long pixels = (long) width * (long) height;
        return width > maxImageWidth || height > maxImageHeight || pixels > maxImagePixels;
    }
}
