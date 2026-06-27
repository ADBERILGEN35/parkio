package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.UserPreferenceEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPreferenceJpaRepository extends JpaRepository<UserPreferenceEntity, UUID> {

    Optional<UserPreferenceEntity> findByUserProfileId(UUID userProfileId);

    @Query(value = """
            SELECT * FROM user_preferences
            WHERE smart_return_enabled = true
              AND notifications_enabled = true
              AND home_latitude IS NOT NULL
              AND home_longitude IS NOT NULL
              AND (last_smart_return_prompt_date IS NULL OR last_smart_return_prompt_date <> :promptDate)
            ORDER BY user_profile_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UserPreferenceEntity> claimDueSmartReturnPrompts(
            @Param("promptDate") LocalDate promptDate,
            @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM user_preferences
            WHERE smart_return_enabled = true
              AND notifications_enabled = true
              AND home_latitude IS NOT NULL
              AND home_longitude IS NOT NULL
              AND (smart_return_today_status = 'LEFT_BY_CAR'
                   OR (smart_return_today_status = 'RETURN_CHECK_IN_PROGRESS'
                       AND today_return_check_claim_expires_at <= :now))
              AND today_expected_return_at IS NOT NULL
              AND today_return_check_completed_at IS NULL
              AND today_expected_return_at - (reminder_lead_minutes * interval '1 minute') <= :now
            ORDER BY today_expected_return_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UserPreferenceEntity> claimDueSmartReturnChecks(@Param("now") Instant now, @Param("limit") int limit);
}
