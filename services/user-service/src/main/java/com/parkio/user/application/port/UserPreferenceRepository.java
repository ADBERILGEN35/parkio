package com.parkio.user.application.port;

import com.parkio.user.domain.UserPreference;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link UserPreference}. */
public interface UserPreferenceRepository {

    UserPreference save(UserPreference preference);

    Optional<UserPreference> findByUserProfileId(UUID userProfileId);

    List<UserPreference> claimDueSmartReturnPrompts(LocalDate promptDate, int limit);

    List<UserPreference> claimDueSmartReturnChecks(Instant now, int limit);
}
