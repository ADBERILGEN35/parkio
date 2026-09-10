package com.parkio.user.domain.place;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * User-owned recent trip-target destination (WP-SPA-07).
 *
 * <p>Written only after explicit destination confirmation — never from keystrokes,
 * geocoder suggestions, or impressions. Not a SavedPlace, favourite, or parking session.
 *
 * <p>Duplicate identity (never label alone), matching favourite-destination policy:
 * <ol>
 *   <li>PlaceIdentity when present → {@code identity:provider:providerPlaceId}</li>
 *   <li>otherwise coordinates rounded to 5 decimal places → {@code coord:lat:lng}</li>
 * </ol>
 *
 * <p>Display refresh policy on repeat confirmation: label and subtitle are refreshed
 * from the newly confirmed Destination; coordinates, source, PlaceIdentity and
 * duplicateKey remain the identity established on first confirmation.
 */
public final class RecentDestination {

    public static final int DEFAULT_MAX_PER_USER = 20;
    public static final int MAX_LABEL_LENGTH = 512;
    public static final int MAX_SUBTITLE_LENGTH = 256;
    public static final int COORD_SCALE = 5;

    private final UUID id;
    private final UUID userProfileId;
    private String label;
    private final double latitude;
    private final double longitude;
    private final PlaceDestinationSource source;
    private final PlaceIdentity placeIdentity;
    private String subtitle;
    private final String duplicateKey;
    private final Instant firstUsedAt;
    private Instant lastUsedAt;
    private long useCount;
    private final Long version;

    public RecentDestination(
            UUID id,
            UUID userProfileId,
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle,
            String duplicateKey,
            Instant firstUsedAt,
            Instant lastUsedAt,
            long useCount,
            Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        this.source = Objects.requireNonNull(source, "source");
        this.firstUsedAt = Objects.requireNonNull(firstUsedAt, "firstUsedAt");
        this.lastUsedAt = Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        if (useCount < 1) {
            throw new IllegalArgumentException("useCount must be >= 1");
        }
        this.useCount = useCount;
        this.latitude = SavedPlace.requireLatitude(latitude);
        this.longitude = SavedPlace.requireLongitude(longitude);
        this.placeIdentity = placeIdentity;
        this.subtitle = SavedPlace.normalizeOptionalText(subtitle, MAX_SUBTITLE_LENGTH, "subtitle");
        this.label = SavedPlace.normalizeRequiredText(label, MAX_LABEL_LENGTH, "label");
        if (this.label.isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        this.duplicateKey = duplicateKey != null && !duplicateKey.isBlank()
                ? duplicateKey
                : computeDuplicateKey(this.latitude, this.longitude, this.placeIdentity);
        this.version = version;
    }

    public static RecentDestination create(
            UUID userProfileId,
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle,
            Instant now) {
        double lat = SavedPlace.requireLatitude(latitude);
        double lng = SavedPlace.requireLongitude(longitude);
        return new RecentDestination(
                UUID.randomUUID(),
                userProfileId,
                label,
                lat,
                lng,
                source == null ? PlaceDestinationSource.MAP_PIN : source,
                placeIdentity,
                subtitle,
                computeDuplicateKey(lat, lng, placeIdentity),
                now,
                now,
                1L,
                null);
    }

    /**
     * Repeat confirmation: bump recency/useCount and refresh display fields only.
     */
    public void recordConfirmation(String label, String subtitle, Instant now) {
        this.label = SavedPlace.normalizeRequiredText(label, MAX_LABEL_LENGTH, "label");
        if (this.label.isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        this.subtitle = SavedPlace.normalizeOptionalText(subtitle, MAX_SUBTITLE_LENGTH, "subtitle");
        this.lastUsedAt = Objects.requireNonNull(now, "now");
        this.useCount += 1;
    }

    public static String computeDuplicateKey(double latitude, double longitude, PlaceIdentity identity) {
        if (identity != null) {
            return "identity:" + identity.provider() + ":" + identity.providerPlaceId();
        }
        return "coord:" + roundCoord(latitude) + ":" + roundCoord(longitude);
    }

    static String roundCoord(double value) {
        return BigDecimal.valueOf(value)
                .setScale(COORD_SCALE, RoundingMode.HALF_UP)
                .toPlainString();
    }

    public Optional<PlaceIdentity> placeIdentityOptional() {
        return Optional.ofNullable(placeIdentity);
    }

    public Optional<String> subtitleOptional() {
        return Optional.ofNullable(subtitle);
    }

    public UUID id() {
        return id;
    }

    public UUID userProfileId() {
        return userProfileId;
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

    public String duplicateKey() {
        return duplicateKey;
    }

    public Instant firstUsedAt() {
        return firstUsedAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public long useCount() {
        return useCount;
    }

    public Long version() {
        return version;
    }
}
