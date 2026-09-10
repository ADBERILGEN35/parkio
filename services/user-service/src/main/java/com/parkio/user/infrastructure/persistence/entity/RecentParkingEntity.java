package com.parkio.user.infrastructure.persistence.entity;

import com.parkio.user.domain.place.RecentParkingTargetKind;
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
@Table(name = "recent_parking")
public class RecentParkingEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_profile_id", nullable = false, updatable = false)
    private UUID userProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, length = 32)
    private RecentParkingTargetKind targetKind;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "first_used_at", nullable = false, updatable = false)
    private Instant firstUsedAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "use_count", nullable = false)
    private long useCount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected RecentParkingEntity() {
    }

    public RecentParkingEntity(
            UUID id,
            UUID userProfileId,
            RecentParkingTargetKind targetKind,
            UUID targetId,
            Instant firstUsedAt,
            Instant lastUsedAt,
            long useCount,
            Long version) {
        this.id = id;
        this.userProfileId = userProfileId;
        this.targetKind = targetKind;
        this.targetId = targetId;
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

    public RecentParkingTargetKind getTargetKind() {
        return targetKind;
    }

    public UUID getTargetId() {
        return targetId;
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
