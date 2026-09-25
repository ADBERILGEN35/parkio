package com.parkio.user.infrastructure.persistence.entity;

import com.parkio.user.domain.place.PlaceDestinationSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recent_destinations")
public class RecentDestinationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_profile_id", nullable = false, updatable = false)
    private UUID userProfileId;

    @Column(name = "label", nullable = false, length = 512)
    private String label;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private PlaceDestinationSource source;

    @Column(name = "place_provider", length = 64)
    private String placeProvider;

    @Column(name = "place_provider_place_id", length = 256)
    private String placeProviderPlaceId;

    @Column(name = "subtitle", length = 256)
    private String subtitle;

    @Column(name = "duplicate_key", nullable = false, length = 384)
    private String duplicateKey;

    @Column(name = "first_used_at", nullable = false, updatable = false)
    private Instant firstUsedAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "use_count", nullable = false)
    private long useCount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected RecentDestinationEntity() {
    }

    public RecentDestinationEntity(
            UUID id,
            UUID userProfileId,
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            String placeProvider,
            String placeProviderPlaceId,
            String subtitle,
            String duplicateKey,
            Instant firstUsedAt,
            Instant lastUsedAt,
            long useCount,
            Long version) {
        this.id = id;
        this.userProfileId = userProfileId;
        this.label = label;
        this.latitude = latitude;
        this.longitude = longitude;
        this.source = source;
        this.placeProvider = placeProvider;
        this.placeProviderPlaceId = placeProviderPlaceId;
        this.subtitle = subtitle;
        this.duplicateKey = duplicateKey;
        this.firstUsedAt = firstUsedAt;
        this.lastUsedAt = lastUsedAt;
        this.useCount = useCount;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserProfileId() {
        return userProfileId;
    }

    public String getLabel() {
        return label;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public PlaceDestinationSource getSource() {
        return source;
    }

    public String getPlaceProvider() {
        return placeProvider;
    }

    public String getPlaceProviderPlaceId() {
        return placeProviderPlaceId;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public String getDuplicateKey() {
        return duplicateKey;
    }

    public Instant getFirstUsedAt() {
        return firstUsedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public long getUseCount() {
        return useCount;
    }

    public Long getVersion() {
        return version;
    }
}
