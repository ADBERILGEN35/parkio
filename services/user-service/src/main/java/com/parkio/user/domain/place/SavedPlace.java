package com.parkio.user.domain.place;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * User-owned reusable destination shortcut (HOME / WORK / CUSTOM).
 *
 * <p>Not a parking facility, community spot, recommendation, favourite, or
 * ParkingSession. HOME and WORK uniqueness is enforced per user_profile_id.
 *
 * <p>Label policy: CUSTOM requires a user-facing label. HOME/WORK may omit a
 * stored label (clients localize from kind); when present, label is an optional
 * display override.
 */
public final class SavedPlace {

    public static final int MAX_LABEL_LENGTH = 512;
    public static final int MAX_SUBTITLE_LENGTH = 256;
    public static final int MAX_CUSTOM_PLACES_PER_USER = 20;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final UUID id;
    private final UUID userProfileId;
    private final SavedPlaceKind kind;
    private String label;
    private double latitude;
    private double longitude;
    private PlaceDestinationSource source;
    private PlaceIdentity placeIdentity;
    private String subtitle;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    public SavedPlace(
            UUID id,
            UUID userProfileId,
            SavedPlaceKind kind,
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle,
            Instant createdAt,
            Instant updatedAt,
            Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.source = Objects.requireNonNull(source, "source");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.latitude = requireLatitude(latitude);
        this.longitude = requireLongitude(longitude);
        this.placeIdentity = placeIdentity;
        this.subtitle = normalizeOptionalText(subtitle, MAX_SUBTITLE_LENGTH, "subtitle");
        this.label = normalizeLabelForKind(kind, label);
        this.version = version;
    }

    public static SavedPlace create(
            UUID userProfileId,
            SavedPlaceKind kind,
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle,
            Instant now) {
        return new SavedPlace(
                UUID.randomUUID(),
                userProfileId,
                kind,
                label,
                latitude,
                longitude,
                source,
                placeIdentity,
                subtitle,
                now,
                now,
                null);
    }

    public void replaceLocation(
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle,
            Instant now) {
        this.source = Objects.requireNonNull(source, "source");
        this.latitude = requireLatitude(latitude);
        this.longitude = requireLongitude(longitude);
        this.placeIdentity = placeIdentity;
        this.subtitle = normalizeOptionalText(subtitle, MAX_SUBTITLE_LENGTH, "subtitle");
        this.label = normalizeLabelForKind(this.kind, label);
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public Optional<PlaceIdentity> placeIdentityOptional() {
        return Optional.ofNullable(placeIdentity);
    }

    public Optional<String> subtitleOptional() {
        return Optional.ofNullable(subtitle);
    }

    public Optional<String> labelOptional() {
        return Optional.ofNullable(label);
    }

    /** Effective display label: stored override or semantic default for HOME/WORK. */
    public String displayLabel() {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return switch (kind) {
            case HOME -> "Home";
            case WORK -> "Work";
            case CUSTOM -> throw new IllegalStateException("CUSTOM place requires a label");
        };
    }

    static String normalizeLabelForKind(SavedPlaceKind kind, String raw) {
        if (kind == SavedPlaceKind.CUSTOM) {
            String normalized = normalizeRequiredText(raw, MAX_LABEL_LENGTH, "label");
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("CUSTOM place requires a non-blank label");
            }
            return normalized;
        }
        return normalizeOptionalText(raw, MAX_LABEL_LENGTH, "label");
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

    public UUID id() {
        return id;
    }

    public UUID userProfileId() {
        return userProfileId;
    }

    public SavedPlaceKind kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public PlaceDestinationSource source() {
        return source;
    }

    public PlaceIdentity placeIdentity() {
        return placeIdentity;
    }

    public String subtitle() {
        return subtitle;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }
}
