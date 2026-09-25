package com.parkio.parking.domain.place;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Canonical trip-target place: where the user intends to go.
 *
 * <p>Not a parking facility, community spot, map camera center, or SavedPlace.
 * Optional {@link PlaceIdentity} is additive; coordinates + label remain valid
 * when no provider id exists.
 *
 * <p>This type carries no parking availability, score, favourite, or recent state.
 */
public record Destination(
        String label,
        double latitude,
        double longitude,
        DestinationSource source,
        PlaceIdentity placeIdentity,
        String subtitle) {

    public static final int MAX_LABEL_LENGTH = 512;
    public static final int MAX_SUBTITLE_LENGTH = 256;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public Destination {
        Objects.requireNonNull(source, "source");
        label = normalizeLabel(label);
        subtitle = normalizeOptionalText(subtitle, MAX_SUBTITLE_LENGTH, "subtitle");
        latitude = requireLatitude(latitude);
        longitude = requireLongitude(longitude);
        // placeIdentity may be null
    }

    public Optional<PlaceIdentity> placeIdentityOptional() {
        return Optional.ofNullable(placeIdentity);
    }

    public Optional<String> subtitleOptional() {
        return Optional.ofNullable(subtitle);
    }

    public static Destination of(
            String label,
            double latitude,
            double longitude,
            DestinationSource source) {
        return new Destination(label, latitude, longitude, source, null, null);
    }

    public static Destination of(
            String label,
            double latitude,
            double longitude,
            DestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle) {
        return new Destination(label, latitude, longitude, source, placeIdentity, subtitle);
    }

    public static Destination mapPin(String label, double latitude, double longitude) {
        return of(label, latitude, longitude, DestinationSource.MAP_PIN);
    }

    static String normalizeLabel(String raw) {
        String normalized = normalizeRequiredText(raw, MAX_LABEL_LENGTH, "label");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        return normalized;
    }

    static String normalizeRequiredText(String raw, int maxLength, String field) {
        if (raw == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        String collapsed = WHITESPACE.matcher(raw.trim()).replaceAll(" ");
        if (collapsed.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength);
        }
        return collapsed;
    }

    static String normalizeOptionalText(String raw, int maxLength, String field) {
        if (raw == null) {
            return null;
        }
        String collapsed = WHITESPACE.matcher(raw.trim()).replaceAll(" ");
        if (collapsed.isEmpty()) {
            return null;
        }
        if (collapsed.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength);
        }
        return collapsed;
    }

    static double requireLatitude(double latitude) {
        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        return latitude;
    }

    static double requireLongitude(double longitude) {
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
        return longitude;
    }
}
