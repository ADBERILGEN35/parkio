package com.parkio.aivalidation.application.port;

import com.parkio.aivalidation.domain.AiValidationResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link AiValidationResult} (with its findings and fit estimates). */
public interface AiValidationResultRepository {

    AiValidationResult save(AiValidationResult result);

    Optional<AiValidationResult> findById(UUID id);

    List<AiValidationResult> findByMediaId(UUID mediaId);

    List<AiValidationResult> findByParkingSpotId(UUID parkingSpotId);
}
