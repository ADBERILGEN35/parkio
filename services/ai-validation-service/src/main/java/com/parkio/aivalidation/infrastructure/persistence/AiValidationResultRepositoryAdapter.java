package com.parkio.aivalidation.infrastructure.persistence;

import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.infrastructure.persistence.entity.AiValidationResultEntity;
import com.parkio.aivalidation.infrastructure.persistence.jpa.AiValidationFindingJpaRepository;
import com.parkio.aivalidation.infrastructure.persistence.jpa.AiValidationResultJpaRepository;
import com.parkio.aivalidation.infrastructure.persistence.jpa.VehicleFitEstimateJpaRepository;
import com.parkio.aivalidation.infrastructure.persistence.mapper.AiValidationPersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts the {@link AiValidationResultRepository} port to Spring Data JPA. Persists the
 * result and its child findings/fit estimates together, and reconstructs the full
 * aggregate on read.
 */
@Component
public class AiValidationResultRepositoryAdapter implements AiValidationResultRepository {

    private final AiValidationResultJpaRepository resultJpa;
    private final AiValidationFindingJpaRepository findingJpa;
    private final VehicleFitEstimateJpaRepository fitJpa;

    public AiValidationResultRepositoryAdapter(AiValidationResultJpaRepository resultJpa,
                                               AiValidationFindingJpaRepository findingJpa,
                                               VehicleFitEstimateJpaRepository fitJpa) {
        this.resultJpa = resultJpa;
        this.findingJpa = findingJpa;
        this.fitJpa = fitJpa;
    }

    @Override
    public AiValidationResult save(AiValidationResult result) {
        resultJpa.save(AiValidationPersistenceMapper.toEntity(result));
        result.findings().forEach(f ->
                findingJpa.save(AiValidationPersistenceMapper.toEntity(f, result.id())));
        result.vehicleFitEstimates().forEach(v ->
                fitJpa.save(AiValidationPersistenceMapper.toEntity(v, result.id())));
        return result;
    }

    @Override
    public Optional<AiValidationResult> findById(UUID id) {
        return resultJpa.findById(id).map(this::hydrate);
    }

    @Override
    public List<AiValidationResult> findByMediaId(UUID mediaId) {
        return resultJpa.findByMediaIdOrderByCreatedAtDesc(mediaId).stream().map(this::hydrate).toList();
    }

    @Override
    public List<AiValidationResult> findByParkingSpotId(UUID parkingSpotId) {
        return resultJpa.findByParkingSpotIdOrderByCreatedAtDesc(parkingSpotId).stream()
                .map(this::hydrate).toList();
    }

    private AiValidationResult hydrate(AiValidationResultEntity entity) {
        return AiValidationPersistenceMapper.toDomain(entity,
                findingJpa.findByValidationResultIdOrderByCreatedAt(entity.getId()),
                fitJpa.findByValidationResultIdOrderByCreatedAt(entity.getId()));
    }
}
