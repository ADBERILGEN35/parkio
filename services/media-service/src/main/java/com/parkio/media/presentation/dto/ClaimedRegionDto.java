package com.parkio.media.presentation.dto;

/**
 * Normalized claimed parking region on the uploaded image.
 * Coordinates are relative to image size in {@code [0, 1]}.
 */
public record ClaimedRegionDto(double x, double y, double width, double height) {
}
