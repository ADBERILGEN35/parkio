package com.parkio.aivalidation.application.port;

import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link AiValidationResult} (with its findings and fit estimates). */
public interface AiValidationResultRepository {

    AiValidationResult save(AiValidationResult result);

    Optional<AiValidationResult> findById(UUID id);

    List<AiValidationResult> findByMediaId(UUID mediaId);

    List<AiValidationResult> findByParkingSpotId(UUID parkingSpotId);

    /**
     * Recent results for a status within an age window (oldest inclusive, newest exclusive
     * on the upper bound). Used by the infrastructure-failure recovery sweep.
     */
    List<AiValidationResult> findByStatusAndCreatedAtBetween(
            AiValidationStatus status, Instant oldestInclusive, Instant newestExclusive, int limit);
}
