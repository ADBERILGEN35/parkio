package com.parkio.parking.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.MunicipalSourceFailureClassifier;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Spring-free orchestration; database uniqueness provides the cross-node sync lock. */
public class MunicipalFacilitySyncService {
    private static final Logger log = LoggerFactory.getLogger(MunicipalFacilitySyncService.class);

    private final Map<String, MunicipalParkingSourceAdapter> adapters;
    private final MunicipalDataSourceRepository sources;
    private final MunicipalSourceSyncRunRepository runs;
    private final MunicipalFacilityIngestWriter ingestWriter;
    private final Clock clock;

    public MunicipalFacilitySyncService(
            List<MunicipalParkingSourceAdapter> adapters,
            MunicipalDataSourceRepository sources,
            MunicipalSourceSyncRunRepository runs,
            MunicipalFacilityIngestWriter ingestWriter,
            Clock clock) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(
                MunicipalParkingSourceAdapter::sourceKey, Function.identity()));
        this.sources = sources;
        this.runs = runs;
        this.ingestWriter = ingestWriter;
        this.clock = clock;
    }

    public MunicipalSyncResult sync(String sourceKey) {
        MunicipalParkingSourceAdapter adapter = adapters.get(sourceKey);
        if (adapter == null) throw new IllegalArgumentException("Unknown municipal source: " + sourceKey);
        MunicipalDataSourceRepository.Source source = sources.requireBySourceKey(sourceKey);
        Instant started = clock.instant();
        var runId = runs.tryStart(source.id(), UUID.randomUUID().toString(), started);
        if (runId.isEmpty()) {
            log.info("municipal_sync_skipped sourceKey={} reason=concurrent_run", sourceKey);
            return result(MunicipalSyncRunStatus.SKIPPED, 0, 0, 0, 0, 0, 0, 0, "concurrent_run", null);
        }

        log.info("municipal_sync_start sourceKey={} runId={}", sourceKey, runId.get());
        SchemaFingerprint fingerprint = null;
        try {
            JsonNode payload = adapter.fetch();
            fingerprint = adapter.validateContract(payload);
            Instant fetchedAt = clock.instant();
            List<NormalizedMunicipalFacility> normalized = adapter.normalizeFacilities(payload, fetchedAt);
            Map<String, NormalizedMunicipalOccupancy> occupancy = adapter.normalizeOccupancy(payload, fetchedAt)
                    .stream().collect(Collectors.toMap(NormalizedMunicipalOccupancy::externalId, Function.identity()));
            int inserted = 0, updated = 0, unchanged = 0, occupancyInserted = 0;
            for (NormalizedMunicipalFacility facility : normalized) {
                var persisted = ingestWriter.persistIzumFacility(
                        source.id(),
                        runId.get(),
                        facility,
                        occupancy.get(facility.externalId()),
                        fetchedAt);
                if (persisted.inserted()) inserted++;
                else if (persisted.changed()) updated++;
                else unchanged++;
                if (persisted.occupancyInserted()) occupancyInserted++;
            }
            int received = payload.size();
            int rejected = Math.max(0, received - normalized.size());
            MunicipalSyncRunStatus status = rejected == 0
                    ? MunicipalSyncRunStatus.SUCCESS : MunicipalSyncRunStatus.PARTIAL_SUCCESS;
            MunicipalSyncResult result = result(status, received, normalized.size(), rejected,
                    inserted, updated, unchanged, occupancyInserted, null, null);
            runs.complete(runId.get(), clock.instant(), result, fingerprint, null);
            sources.markSuccessful(source.id(), clock.instant());
            log.info(
                    "municipal_sync_complete sourceKey={} runId={} status={} received={} accepted={} rejected={} "
                            + "inserted={} updated={} unchanged={} occupancyInserted={} errorCategory=none recovery=false",
                    sourceKey, runId.get(), status, received, normalized.size(), rejected,
                    inserted, updated, unchanged, occupancyInserted);
            if (rejected > 0) {
                log.warn("municipal_sync_partial_rejection sourceKey={} rejected={}", sourceKey, rejected);
            }
            return result;
        } catch (RuntimeException failure) {
            String category = MunicipalSourceFailureClassifier.wireValue(failure);
            MunicipalSyncResult result = result(MunicipalSyncRunStatus.FAILED, 0, 0, 0,
                    0, 0, 0, 0, category, truncate(failure.getMessage()));
            runs.complete(runId.get(), clock.instant(), result, fingerprint, null);
            log.warn("municipal_sync_failed sourceKey={} runId={} status=FAILED attempts=final "
                            + "errorCategory={} recovery=false",
                    sourceKey, runId.get(), result.errorCategory());
            return result;
        }
    }

    private static MunicipalSyncResult result(MunicipalSyncRunStatus status, int received, int accepted,
            int rejected, int inserted, int updated, int unchanged, int occupancyInserted,
            String category, String summary) {
        return new MunicipalSyncResult(status, received, accepted, rejected, inserted, updated,
                unchanged, occupancyInserted, category, summary);
    }

    private static String truncate(String message) {
        if (message == null) return null;
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }
}
