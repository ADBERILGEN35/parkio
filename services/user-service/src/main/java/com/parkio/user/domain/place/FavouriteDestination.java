package com.parkio.user.domain.place;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * User-owned favourite trip-target destination snapshot (WP-SPA-04).
 *
 * <p>Separate from {@link SavedPlace}: HOME/WORK/CUSTOM personal shortcuts vs
 * reusable destinations. Same coordinates may exist in both without merging.
 *
 * <p>Duplicate identity (never label alone):
 * <ol>
 *   <li>PlaceIdentity when present → {@code identity:provider:providerPlaceId}</li>
 *   <li>otherwise coordinates rounded to 5 decimal places (~1.1 m) →
 *       {@code coord:lat:lng}</li>
 * </ol>
 * Five-decimal precision collapses GPS jitter for the same pin without merging
 * distinct nearby destinations tens of meters apart.
 */
public final class FavouriteDestination {

    public static final int MAX_PER_USER = 50;
    public static final int MAX_LABEL_LENGTH = 512;
    public static final int MAX_SUBTITLE_LENGTH = 256;
    public static final int COORD_SCALE = 5;

    private final UUID id;
    private final UUID userProfileId;
    private String label;
    private double latitude;
    private double longitude;
    private PlaceDestinationSource source;
    private PlaceIdentity placeIdentity;
    private String subtitle;
    private String duplicateKey;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    public FavouriteDestination(
            UUID id,
            UUID userProfileId,
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle,
            String duplicateKey,
            Instant createdAt,
            Instant updatedAt,
            Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        this.source = Objects.requireNonNull(source, "source");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
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

    public static FavouriteDestination create(
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
        return new FavouriteDestination(
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
                null);
    }

    public void updateDisplay(String label, String subtitle, Instant now) {
        this.label = SavedPlace.normalizeRequiredText(label, MAX_LABEL_LENGTH, "label");
        if (this.label.isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        this.subtitle = SavedPlace.normalizeOptionalText(subtitle, MAX_SUBTITLE_LENGTH, "subtitle");
        this.updatedAt = Objects.requireNonNull(now, "now");
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
