package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.domain.place.SavedPlaceKind;
import com.parkio.user.infrastructure.persistence.entity.SavedPlaceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedPlaceJpaRepository extends JpaRepository<SavedPlaceEntity, UUID> {

    Optional<SavedPlaceEntity> findByIdAndUserProfileId(UUID id, UUID userProfileId);

    Optional<SavedPlaceEntity> findByUserProfileIdAndKind(UUID userProfileId, SavedPlaceKind kind);

    List<SavedPlaceEntity> findByUserProfileIdOrderByKindAscUpdatedAtDesc(UUID userProfileId);

    long countByUserProfileIdAndKind(UUID userProfileId, SavedPlaceKind kind);

    void deleteByIdAndUserProfileId(UUID id, UUID userProfileId);

    void deleteByUserProfileId(UUID userProfileId);

    @Query(value = """
            SELECT up.user_profile_id AS user_profile_id,
                   up.home_latitude AS home_latitude,
                   up.home_longitude AS home_longitude,
                   up.home_label AS home_label
            FROM user_preferences up
            WHERE up.home_latitude IS NOT NULL
              AND up.home_longitude IS NOT NULL
              AND up.home_latitude BETWEEN -90 AND 90
              AND up.home_longitude BETWEEN -180 AND 180
              AND NOT EXISTS (
                  SELECT 1 FROM saved_places sp
                  WHERE sp.user_profile_id = up.user_profile_id AND sp.kind = 'HOME'
              )
            ORDER BY up.user_profile_id
            LIMIT :limit
            """, nativeQuery = true)
    List<LegacyHomeProjection> findLegacyHomesMissingSavedPlace(@Param("limit") int limit);

    interface LegacyHomeProjection {
        UUID getUserProfileId();

        Double getHomeLatitude();

        Double getHomeLongitude();

        String getHomeLabel();
    }
}
