package com.parkio.aivalidation.infrastructure.vision;

/**
 * Normalized claimed parking region on the image under analysis ({@code [0,1]}).
 * Sourced from media-service metadata when the uploader annotated a box.
 */
public record ClaimedRegion(double x, double y, double width, double height) {

    public ClaimedRegion {
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || x + width > 1.0000001 || y + height > 1.0000001) {
            throw new IllegalArgumentException("invalid claimed region");
        }
    }

    public static ClaimedRegion of(double x, double y, double width, double height) {
        return new ClaimedRegion(x, y, width, height);
    }
}
