package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.UserVehicleProfileRepository;
import com.parkio.user.domain.UserVehicleProfile;
import com.parkio.user.infrastructure.persistence.jpa.UserVehicleProfileJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.UserPersistenceMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link UserVehicleProfileRepository} port to Spring Data JPA. */
@Component
public class UserVehicleProfileRepositoryAdapter implements UserVehicleProfileRepository {

    private final UserVehicleProfileJpaRepository jpa;

    public UserVehicleProfileRepositoryAdapter(UserVehicleProfileJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UserVehicleProfile save(UserVehicleProfile vehicle) {
        return UserPersistenceMapper.toDomain(jpa.save(UserPersistenceMapper.toEntity(vehicle)));
    }

    @Override
    public Optional<UserVehicleProfile> findByUserProfileId(UUID userProfileId) {
        return jpa.findByUserProfileId(userProfileId).map(UserPersistenceMapper::toDomain);
    }
}
