package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.UserTrustProfileEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserTrustProfileJpaRepository extends JpaRepository<UserTrustProfileEntity, UUID> {

    Optional<UserTrustProfileEntity> findByUserProfileId(UUID userProfileId);

    void deleteByUserProfileId(UUID userProfileId);

    // Version-guarded gamification snapshots (U12). Each statement is one row-locking UPDATE,
    // so PostgreSQL re-checks the guard against the latest committed row and concurrent
    // deliveries keep the highest version. The JPA version is bumped like any other write.
    // Points and level come from one aggregate (user_level_progress) and share its version
    // sequence, so a version-less level is older than any versioned points snapshot too.

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE user_trust_profiles
               SET total_points = :totalPoints, points_version = :aggregateVersion, version = version + 1
             WHERE user_profile_id = :userProfileId
               AND (points_version IS NULL OR points_version < :aggregateVersion)
            """, nativeQuery = true)
    int projectVersionedTotalPoints(@Param("userProfileId") UUID userProfileId,
                                    @Param("totalPoints") long totalPoints,
                                    @Param("aggregateVersion") long aggregateVersion);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE user_trust_profiles
               SET total_points = :totalPoints, version = version + 1
             WHERE user_profile_id = :userProfileId AND points_version IS NULL
            """, nativeQuery = true)
    int projectUnversionedTotalPoints(@Param("userProfileId") UUID userProfileId,
                                      @Param("totalPoints") long totalPoints);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE user_trust_profiles
               SET current_level = :currentLevel, level_version = :aggregateVersion, version = version + 1
             WHERE user_profile_id = :userProfileId
               AND (level_version IS NULL OR level_version < :aggregateVersion)
            """, nativeQuery = true)
    int projectVersionedLevel(@Param("userProfileId") UUID userProfileId,
                              @Param("currentLevel") int currentLevel,
                              @Param("aggregateVersion") long aggregateVersion);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE user_trust_profiles
               SET current_level = :currentLevel, version = version + 1
             WHERE user_profile_id = :userProfileId AND level_version IS NULL AND points_version IS NULL
            """, nativeQuery = true)
    int projectUnversionedLevel(@Param("userProfileId") UUID userProfileId,
                                @Param("currentLevel") int currentLevel);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE user_trust_profiles
               SET trust_score = :trustScore, trust_band = :trustBand, trust_version = :aggregateVersion,
                   version = version + 1
             WHERE user_profile_id = :userProfileId
               AND (trust_version IS NULL OR trust_version < :aggregateVersion)
            """, nativeQuery = true)
    int projectVersionedTrustScore(@Param("userProfileId") UUID userProfileId,
                                   @Param("trustScore") int trustScore,
                                   @Param("trustBand") String trustBand,
                                   @Param("aggregateVersion") long aggregateVersion);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE user_trust_profiles
               SET trust_score = :trustScore, trust_band = :trustBand, version = version + 1
             WHERE user_profile_id = :userProfileId AND trust_version IS NULL
            """, nativeQuery = true)
    int projectUnversionedTrustScore(@Param("userProfileId") UUID userProfileId,
                                     @Param("trustScore") int trustScore,
                                     @Param("trustBand") String trustBand);
}
