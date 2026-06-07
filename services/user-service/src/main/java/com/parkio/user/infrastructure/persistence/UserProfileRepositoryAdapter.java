package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.infrastructure.persistence.jpa.UserProfileJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.UserPersistenceMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link UserProfileRepository} port to Spring Data JPA. */
@Component
public class UserProfileRepositoryAdapter implements UserProfileRepository {

    private final UserProfileJpaRepository jpa;

    public UserProfileRepositoryAdapter(UserProfileJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UserProfile save(UserProfile profile) {
        return UserPersistenceMapper.toDomain(jpa.save(UserPersistenceMapper.toEntity(profile)));
    }

    @Override
    public Optional<UserProfile> findByAuthUserId(UUID authUserId) {
        return jpa.findByAuthUserId(authUserId).map(UserPersistenceMapper::toDomain);
    }

    @Override
    public boolean existsByAuthUserId(UUID authUserId) {
        return jpa.existsByAuthUserId(authUserId);
    }
}
