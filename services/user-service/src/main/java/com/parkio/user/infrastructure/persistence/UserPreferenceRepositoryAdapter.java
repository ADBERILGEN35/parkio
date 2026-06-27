package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.UserPreferenceRepository;
import com.parkio.user.domain.UserPreference;
import com.parkio.user.infrastructure.persistence.jpa.UserPreferenceJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.UserPersistenceMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link UserPreferenceRepository} port to Spring Data JPA. */
@Component
public class UserPreferenceRepositoryAdapter implements UserPreferenceRepository {

    private final UserPreferenceJpaRepository jpa;

    public UserPreferenceRepositoryAdapter(UserPreferenceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UserPreference save(UserPreference preference) {
        return UserPersistenceMapper.toDomain(jpa.save(UserPersistenceMapper.toEntity(preference)));
    }

    @Override
    public Optional<UserPreference> findByUserProfileId(UUID userProfileId) {
        return jpa.findByUserProfileId(userProfileId).map(UserPersistenceMapper::toDomain);
    }

    @Override
    public List<UserPreference> claimDueSmartReturnPrompts(LocalDate promptDate, int limit) {
        return jpa.claimDueSmartReturnPrompts(promptDate, limit).stream()
                .map(UserPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<UserPreference> claimDueSmartReturnChecks(Instant now, int limit) {
        return jpa.claimDueSmartReturnChecks(now, limit).stream()
                .map(UserPersistenceMapper::toDomain)
                .toList();
    }
}
