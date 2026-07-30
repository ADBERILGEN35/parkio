package com.parkio.parking.application.port;

import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MunicipalSourceSyncRunRepository {
    record LatestRun(String status, String errorCategory, Instant completedAt) {}

    Optional<UUID> tryStart(UUID sourceId, String correlationId, Instant startedAt);
    void complete(UUID runId, Instant completedAt, MunicipalSyncResult result,
                  SchemaFingerprint fingerprint, String payloadHash);
    Optional<LatestRun> findLatestCompleted(UUID sourceId);
}