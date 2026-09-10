package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.SavedPlaceRepository;
import com.parkio.user.domain.place.SavedPlace;
import com.parkio.user.domain.place.SavedPlaceKind;
import com.parkio.user.infrastructure.persistence.jpa.SavedPlaceJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.SavedPlacePersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class SavedPlaceRepositoryAdapter implements SavedPlaceRepository {

    private final SavedPlaceJpaRepository jpa;

    public SavedPlaceRepositoryAdapter(SavedPlaceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public SavedPlace save(SavedPlace place) {
        return SavedPlacePersistenceMapper.toDomain(jpa.save(SavedPlacePersistenceMapper.toEntity(place)));
    }

    @Override
    public Optional<SavedPlace> findByIdAndUserProfileId(UUID id, UUID userProfileId) {
        return jpa.findByIdAndUserProfileId(id, userProfileId).map(SavedPlacePersistenceMapper::toDomain);
    }

    @Override
    public Optional<SavedPlace> findByUserProfileIdAndKind(UUID userProfileId, SavedPlaceKind kind) {
        return jpa.findByUserProfileIdAndKind(userProfileId, kind).map(SavedPlacePersistenceMapper::toDomain);
    }

    @Override
    public List<SavedPlace> findAllByUserProfileId(UUID userProfileId) {
        return jpa.findByUserProfileIdOrderByKindAscUpdatedAtDesc(userProfileId).stream()
                .map(SavedPlacePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countByUserProfileIdAndKind(UUID userProfileId, SavedPlaceKind kind) {
        return jpa.countByUserProfileIdAndKind(userProfileId, kind);
    }

    @Override
    public void deleteByIdAndUserProfileId(UUID id, UUID userProfileId) {
        jpa.deleteByIdAndUserProfileId(id, userProfileId);
    }

    @Override
    public List<LegacyHomeCandidate> findLegacyHomesMissingSavedPlace(int limit) {
        return jpa.findLegacyHomesMissingSavedPlace(limit).stream()
                .map(row -> new LegacyHomeCandidate(
                        row.getUserProfileId(),
                        row.getHomeLatitude(),
                        row.getHomeLongitude(),
                        row.getHomeLabel()))
                .toList();
    }
}
