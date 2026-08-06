package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.FavouriteDestinationRepository;
import com.parkio.user.domain.place.FavouriteDestination;
import com.parkio.user.infrastructure.persistence.jpa.FavouriteDestinationJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.FavouritePersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class FavouriteDestinationRepositoryAdapter implements FavouriteDestinationRepository {

    private final FavouriteDestinationJpaRepository jpa;

    public FavouriteDestinationRepositoryAdapter(FavouriteDestinationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public FavouriteDestination save(FavouriteDestination favourite) {
        return FavouritePersistenceMapper.toDomain(jpa.save(FavouritePersistenceMapper.toEntity(favourite)));
    }

    @Override
    public Optional<FavouriteDestination> findByIdAndUserProfileId(UUID id, UUID userProfileId) {
        return jpa.findByIdAndUserProfileId(id, userProfileId).map(FavouritePersistenceMapper::toDomain);
    }

    @Override
    public Optional<FavouriteDestination> findByUserProfileIdAndDuplicateKey(
            UUID userProfileId, String duplicateKey) {
        return jpa.findByUserProfileIdAndDuplicateKey(userProfileId, duplicateKey)
                .map(FavouritePersistenceMapper::toDomain);
    }

    @Override
    public List<FavouriteDestination> findAllByUserProfileId(UUID userProfileId) {
        return jpa.findByUserProfileIdOrderByUpdatedAtDesc(userProfileId).stream()
                .map(FavouritePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countByUserProfileId(UUID userProfileId) {
        return jpa.countByUserProfileId(userProfileId);
    }

    @Override
    public void deleteByIdAndUserProfileId(UUID id, UUID userProfileId) {
        jpa.deleteByIdAndUserProfileId(id, userProfileId);
    }
}
