package com.parkio.user.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Trust + gamification PROJECTION for a user (1:1 with a {@link UserProfile}).
 * These are read copies maintained from gamification events ({@code PointsEarned},
 * {@code PointsDeducted}, {@code UserLevelChanged}, {@code TrustScoreUpdated});
 * user-service never computes them (ai-context/02, 03). Events change them only through
 * {@code UserTrustProfileRepository}'s version-guarded updates, so an older snapshot never
 * overwrites a newer one (U12). New profiles start at trust score 100
 * ({@link TrustBand#HIGH_TRUST}), 0 points, level 1.
 */
public final class UserTrustProfile {

    public static final int INITIAL_TRUST_SCORE = 100;
    public static final long INITIAL_TOTAL_POINTS = 0L;
    public static final int INITIAL_LEVEL = 1;

    private final UUID id;
    private final UUID userProfileId;
    private final int trustScore;
    private final TrustBand trustBand;
    private final long totalPoints;
    private final int currentLevel;
    private final Long version;

    public UserTrustProfile(UUID id,
                            UUID userProfileId,
                            int trustScore,
                            TrustBand trustBand,
                            long totalPoints,
                            int currentLevel,
                            Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        this.trustScore = trustScore;
        this.trustBand = Objects.requireNonNull(trustBand, "trustBand");
        this.totalPoints = totalPoints;
        this.currentLevel = currentLevel;
        this.version = version;
    }

    /** Creates the default projection for a newly-created profile. */
    public static UserTrustProfile createDefault(UUID userProfileId) {
        return new UserTrustProfile(
                UUID.randomUUID(),
                userProfileId,
                INITIAL_TRUST_SCORE,
                TrustBand.HIGH_TRUST,
                INITIAL_TOTAL_POINTS,
                INITIAL_LEVEL,
                null);
    }

    public UUID id() {
        return id;
    }

    public UUID userProfileId() {
        return userProfileId;
    }

    public int trustScore() {
        return trustScore;
    }

    public TrustBand trustBand() {
        return trustBand;
    }

    public long totalPoints() {
        return totalPoints;
    }

    public int currentLevel() {
        return currentLevel;
    }

    public Long version() {
        return version;
    }
}
