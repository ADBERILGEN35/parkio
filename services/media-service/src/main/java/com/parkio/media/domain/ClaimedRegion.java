package com.parkio.media.domain;

/**
 * Axis-aligned box marking the parking space the uploader claims is available.
 * Coordinates are normalized to the uploaded image: {@code x}/{@code y} top-left,
 * {@code width}/{@code height} extents, all in {@code [0, 1]}.
 */
public record ClaimedRegion(double x, double y, double width, double height) {

    /** Minimum fraction of image area required for a usable annotation. */
    public static final double MIN_AREA = 0.05;

    public ClaimedRegion {
        requireFinite("x", x);
        requireFinite("y", y);
        requireFinite("width", width);
        requireFinite("height", height);
        if (x < 0 || x > 1 || y < 0 || y > 1) {
            throw new IllegalArgumentException("claimed region origin must be within [0, 1]");
        }
        if (width <= 0 || height <= 0 || width > 1 || height > 1) {
            throw new IllegalArgumentException("claimed region size must be in (0, 1]");
        }
        if (x + width > 1.0000001 || y + height > 1.0000001) {
            throw new IllegalArgumentException("claimed region must fit inside the image");
        }
        if (width * height < MIN_AREA) {
            throw new IllegalArgumentException(
                    "claimed region must cover at least " + (int) (MIN_AREA * 100) + "% of the image");
        }
    }

    public double area() {
        return width * height;
    }

    public static ClaimedRegion of(double x, double y, double width, double height) {
        return new ClaimedRegion(x, y, width, height);
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("claimed region " + name + " must be a finite number");
        }
    }
}