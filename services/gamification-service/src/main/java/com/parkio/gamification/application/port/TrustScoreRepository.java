package com.parkio.gamification.application.port;

import com.parkio.gamification.domain.TrustScore;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link TrustScore} aggregates. */
public interface TrustScoreRepository {

    TrustScore save(TrustScore trustScore);

    Optional<TrustScore> findByUserId(UUID userId);
}
