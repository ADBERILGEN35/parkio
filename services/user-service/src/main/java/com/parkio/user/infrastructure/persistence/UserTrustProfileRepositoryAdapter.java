package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.UserTrustProfileRepository;
import com.parkio.user.domain.UserTrustProfile;
import com.parkio.user.infrastructure.persistence.jpa.UserTrustProfileJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.UserPersistenceMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link UserTrustProfileRepository} port to Spring Data JPA. */
@Component
public class UserTrustProfileRepositoryAdapter implements UserTrustProfileRepository {

    private final UserTrustProfileJpaRepository jpa;

    public UserTrustProfileRepositoryAdapter(UserTrustProfileJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UserTrustProfile save(UserTrustProfile trustProfile) {
        return UserPersistenceMapper.toDomain(jpa.save(UserPersistenceMapper.toEntity(trustProfile)));
    }

    @Override
    public Optional<UserTrustProfile> findByUserProfileId(UUID userProfileId) {
        return jpa.findByUserProfileId(userProfileId).map(UserPersistenceMapper::toDomain);
    }
}
