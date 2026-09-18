package com.parkio.parking.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MunicipalDataSourceRepository {
    record Source(UUID id, String sourceKey, String publisher, String attribution,
                  long agingAfterSeconds, long staleAfterSeconds, Instant lastSuccessfulSyncAt,
                  boolean completeSnapshot) {}
    Source requireBySourceKey(String sourceKey);
    Optional<Source> findBySourceKey(String sourceKey);
    void markSuccessful(UUID sourceId, Instant completedAt);
}
