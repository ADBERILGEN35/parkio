package com.parkio.aivalidation.infrastructure.persistence.mapper;

import com.parkio.aivalidation.domain.AiValidationFinding;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.VehicleFitEstimate;
import com.parkio.aivalidation.infrastructure.persistence.entity.AiValidationFindingEntity;
import com.parkio.aivalidation.infrastructure.persistence.entity.AiValidationResultEntity;
import com.parkio.aivalidation.infrastructure.persistence.entity.VehicleFitEstimateEntity;
import java.util.List;

/**
 * Maps between domain aggregates and JPA entities. Static, stateless: persistence
 * never leaks into the domain and vice versa.
 */
public final class AiValidationPersistenceMapper {

    private AiValidationPersistenceMapper() {
    }

    public static AiValidationResultEntity toEntity(AiValidationResult r) {
        return new AiValidationResultEntity(
                r.id(), r.mediaId(), r.parkingSpotId().orElse(null), r.requestedByUserId().orElse(null),
                r.status(), r.emptySpaceConfidence(), r.legalRiskScore(), r.imageQualityScore(),
                r.aiConfidence(), r.createdAt(), r.version());
    }

    public static AiValidationFindingEntity toEntity(AiValidationFinding f, java.util.UUID resultId) {
        return new AiValidationFindingEntity(
                f.id(), resultId, f.validationType(), f.riskType().orElse(null),
                f.score(), f.message(), f.createdAt());
    }

    public static VehicleFitEstimateEntity toEntity(VehicleFitEstimate v, java.util.UUID resultId) {
        return new VehicleFitEstimateEntity(v.id(), resultId, v.vehicleType(), v.fitScore(), v.createdAt());
    }

    public static AiValidationResult toDomain(AiValidationResultEntity e,
                                              List<AiValidationFindingEntity> findings,
                                              List<VehicleFitEstimateEntity> fits) {
        return new AiValidationResult(
                e.getId(), e.getMediaId(), e.getParkingSpotId(), e.getRequestedByUserId(), e.getStatus(),
                e.getEmptySpaceConfidence(), e.getLegalRiskScore(), e.getImageQualityScore(), e.getAiConfidence(),
                findings.stream().map(AiValidationPersistenceMapper::toDomain).toList(),
                fits.stream().map(AiValidationPersistenceMapper::toDomain).toList(),
                e.getCreatedAt(), e.getVersion());
    }

    private static AiValidationFinding toDomain(AiValidationFindingEntity e) {
        return new AiValidationFinding(e.getId(), e.getValidationType(), e.getRiskType(),
                e.getScore(), e.getMessage(), e.getCreatedAt());
    }

    private static VehicleFitEstimate toDomain(VehicleFitEstimateEntity e) {
        return new VehicleFitEstimate(e.getId(), e.getVehicleType(), e.getFitScore(), e.getCreatedAt());
    }
}
