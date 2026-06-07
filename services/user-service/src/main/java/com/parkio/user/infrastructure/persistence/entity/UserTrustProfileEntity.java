package com.parkio.user.infrastructure.persistence.entity;

import com.parkio.user.domain.TrustBand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/** JPA mapping for the {@code user_trust_profiles} projection. */
@Entity
@Table(name = "user_trust_profiles")
public class UserTrustProfileEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_profile_id", nullable = false, unique = true, updatable = false)
    private UUID userProfileId;

    @Column(name = "trust_score", nullable = false)
    private int trustScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_band", nullable = false)
    private TrustBand trustBand;

    @Column(name = "total_points", nullable = false)
    private long totalPoints;

    @Column(name = "current_level", nullable = false)
    private int currentLevel;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected UserTrustProfileEntity() {
        // for JPA
    }

    public UserTrustProfileEntity(UUID id,
                                  UUID userProfileId,
                                  int trustScore,
                                  TrustBand trustBand,
                                  long totalPoints,
                                  int currentLevel,
                                  Long version) {
        this.id = id;
        this.userProfileId = userProfileId;
        this.trustScore = trustScore;
        this.trustBand = trustBand;
        this.totalPoints = totalPoints;
        this.currentLevel = currentLevel;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserProfileId() {
        return userProfileId;
    }

    public int getTrustScore() {
        return trustScore;
    }

    public TrustBand getTrustBand() {
        return trustBand;
    }

    public long getTotalPoints() {
        return totalPoints;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public Long getVersion() {
        return version;
    }
}
