package com.parkio.parking.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.application.port.OsmImportSupportRepository;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.MunicipalSourceFailureCategory;
import com.parkio.parking.externalsource.MunicipalSourceFailureClassifier;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Spring-free orchestration; database uniqueness provides the cross-node sync lock. */
public class MunicipalFacilitySyncService {
    private static final Logger log = LoggerFactory.getLogger(MunicipalFacilitySyncService.class);
    /** Warn when a successful reconciliation deactivates more than half of prior active links. */
    private static final double LARGE_SHRINK_RATIO = 0.5d;

    private final Map<String, MunicipalParkingSourceAdapter> adapters;
    private final MunicipalDataSourceRepository sources;
    private final MunicipalSourceSyncRunRepository runs;
    private final MunicipalFacilityIngestWriter ingestWriter;
    private final OsmImportSupportRepository setReconciliation;
    private final Clock clock;

    public MunicipalFacilitySyncService(
            List<MunicipalParkingSourceAdapter> adapters,
            MunicipalDataSourceRepository sources,
            MunicipalSourceSyncRunRepository runs,
            MunicipalFacilityIngestWriter ingestWriter,
            OsmImportSupportRepository setReconciliation,
            Clock clock) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(
                MunicipalParkingSourceAdapter::sourceKey, Function.identity()));
        this.sources = sources;
        this.runs = runs;
        this.ingestWriter = ingestWriter;
        this.setReconciliation = setReconciliation;
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
            return result(MunicipalSyncRunStatus.SKIPPED, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    "concurrent_run", null);
        }

        log.info("municipal_sync_start sourceKey={} runId={}", sourceKey, runId.get());
        SchemaFingerprint fingerprint = null;
        try {
            JsonNode payload = adapter.fetch();
            fingerprint = adapter.validateContract(payload);
            // Authoritative reconciliation needs a trust signal that is independent from how adapters
            // filter "non-active" members (e.g. inventory-only feeds where active=false is legitimate).
            int authoritativeValidUniqueExternalIds =
                    adapter.countAuthoritativeValidUniqueFacilityExternalIds(payload);
            Instant fetchedAt = clock.instant();
            List<NormalizedMunicipalFacility> normalized = adapter.normalizeFacilities(payload, fetchedAt);
            Map<String, NormalizedMunicipalOccupancy> occupancy = adapter.normalizeOccupancy(payload, fetchedAt)
                    .stream().collect(Collectors.toMap(NormalizedMunicipalOccupancy::externalId, Function.identity()));

            Set<String> previouslyActive = Set.copyOf(setReconciliation.activeExternalIds(source.id()));
            int inserted = 0, updated = 0, unchanged = 0, occupancyInserted = 0, reactivated = 0;
            Set<String> seen = new HashSet<>();
            for (NormalizedMunicipalFacility facility : normalized) {
                seen.add(facility.externalId());
                var persisted = ingestWriter.persistLiveAdapterFacility(
                        source.id(),
                        runId.get(),
                        sourceKey,
                        facility,
                        occupancy.get(facility.externalId()),
                        fetchedAt);
                if (persisted.inserted()) inserted++;
                else if (persisted.changed()) updated++;
                else unchanged++;
                if (persisted.occupancyInserted()) occupancyInserted++;
                // Existing facility/link row that was not previously active → reactivated by upsert.
                if (!persisted.inserted() && !previouslyActive.contains(facility.externalId())) {
                    reactivated++;
                }
            }

            // Authoritative missing-set reconciliation only after a fully successful, non-empty feed.
            int received = payload.size();
            int accepted = normalized.size();
            int rejected = Math.max(0, received - accepted);
            MunicipalSyncRunStatus status = rejected == 0
                    ? MunicipalSyncRunStatus.SUCCESS : MunicipalSyncRunStatus.PARTIAL_SUCCESS;

            int deactivated = 0;
            if (isAuthoritativeSet(
                    adapter,
                    status,
                    accepted,
                    seen,
                    authoritativeValidUniqueExternalIds,
                    received)) {
                // Never deactivate unless this execution still owns the RUNNING lease.
                if (!runs.isRunning(runId.get())) {
                    log.warn(
                            "municipal_sync_ownership_lost sourceKey={} runId={} phase=before_reconcile",
                            sourceKey, runId.get());
                    return ownershipLost();
                }
                deactivated = setReconciliation.deactivateMissing(source.id(), seen, fetchedAt);
                if (previouslyActive.size() > 0
                        && deactivated > previouslyActive.size() * LARGE_SHRINK_RATIO) {
                    log.warn(
                            "municipal_sync_large_shrink sourceKey={} previouslyActive={} deactivated={} accepted={}",
                            sourceKey, previouslyActive.size(), deactivated, accepted);
                }
            }

            int activeLinkCount = setReconciliation.activeExternalIds(source.id()).size();
            if (status == MunicipalSyncRunStatus.SUCCESS && accepted != seen.size()) {
                log.warn(
                        "municipal_sync_set_mismatch sourceKey={} accepted={} uniqueSeen={}",
                        sourceKey, accepted, seen.size());
            }
            if (status == MunicipalSyncRunStatus.SUCCESS
                    && isAuthoritativeSet(
                            adapter,
                            status,
                            accepted,
                            seen,
                            authoritativeValidUniqueExternalIds,
                            received)
                    && activeLinkCount > seen.size()) {
                log.warn(
                        "municipal_sync_active_exceeds_set sourceKey={} activeLinks={} authoritativeSet={}",
                        sourceKey, activeLinkCount, seen.size());
            }

            MunicipalSyncResult result = result(status, received, accepted, rejected,
                    inserted, updated, unchanged, occupancyInserted, deactivated, reactivated,
                    activeLinkCount, null, null);
            if (!runs.complete(runId.get(), clock.instant(), result, fingerprint, null)) {
                log.warn(
                        "municipal_sync_complete_ignored sourceKey={} runId={} reason=ownership_lost",
                        sourceKey, runId.get());
                return ownershipLost();
            }
            sources.markSuccessful(source.id(), clock.instant());
            log.info(
                    "municipal_sync_complete sourceKey={} runId={} status={} received={} accepted={} rejected={} "
                            + "inserted={} updated={} unchanged={} occupancyInserted={} deactivated={} reactivated={} "
                            + "activeLinks={} uniqueUfid={} errorCategory=none recovery=false",
                    sourceKey, runId.get(), status, received, accepted, rejected,
                    inserted, updated, unchanged, occupancyInserted, deactivated, reactivated,
                    activeLinkCount, seen.size());
            if (rejected > 0) {
                log.warn("municipal_sync_partial_rejection sourceKey={} rejected={}", sourceKey, rejected);
            }
            return result;
        } catch (RuntimeException failure) {
            String category = MunicipalSourceFailureClassifier.wireValue(failure);
            MunicipalSyncResult result = result(MunicipalSyncRunStatus.FAILED, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, category, truncate(failure.getMessage()));
            if (!runs.complete(runId.get(), clock.instant(), result, fingerprint, null)) {
                log.warn(
                        "municipal_sync_complete_ignored sourceKey={} runId={} reason=ownership_lost "
                                + "originalCategory={}",
                        sourceKey, runId.get(), category);
                return ownershipLost();
            }
            log.warn("municipal_sync_failed sourceKey={} runId={} status=FAILED attempts=final "
                            + "errorCategory={} recovery=false",
                    sourceKey, runId.get(), result.errorCategory());
            return result;
        }
    }

    /**
     * Missing-set soft-deactivation runs only for {@link ReconciliationMode#AUTHORITATIVE_FULL_SET}
     * sources after a fully successful non-empty validated feed. Partial success, empty feeds,
     * and failures never mass-deactivate. Policy is source-scoped (never cross-provider).
     */
    static boolean isAuthoritativeSet(
            MunicipalParkingSourceAdapter adapter,
            MunicipalSyncRunStatus status,
            int accepted,
            Set<String> seen,
            int authoritativeValidUniqueExternalIds,
            int received) {
        ReconciliationMode mode = adapter != null
                ? adapter.reconciliationMode()
                : ReconciliationMode.UPSERT_ONLY;
        // Trustworthiness check:
        // - authoritativeValidUniqueExternalIds counts structurally valid unique members
        //   (adapter-specific; for ANPARK it includes active=false rows).
        // - received is the authoritative snapshot cardinality from fetch() output.
        // Reconciliation to an empty active set is allowed ONLY when the authoritative snapshot
        // is structurally trustworthy (valid unique ids == received > 0).
        return mode == ReconciliationMode.AUTHORITATIVE_FULL_SET
                && authoritativeValidUniqueExternalIds > 0
                && authoritativeValidUniqueExternalIds == received
                && seen.size() == accepted;
    }

    /**
     * @deprecated Prefer {@link #isAuthoritativeSet(MunicipalParkingSourceAdapter, MunicipalSyncRunStatus, int, Set)}.
     */
    @Deprecated
    static boolean isAuthoritativeSet(
            String sourceKey, MunicipalSyncRunStatus status, int accepted, Set<String> seen) {
        return ParkingProviderCatalog.isAuthoritativeFullSet(sourceKey)
                && status == MunicipalSyncRunStatus.SUCCESS
                && accepted > 0
                && !seen.isEmpty()
                && seen.size() == accepted;
    }

    private static MunicipalSyncResult ownershipLost() {
        return result(
                MunicipalSyncRunStatus.FAILED,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                MunicipalSourceFailureCategory.OWNERSHIP_LOST.wireValue(),
                "run ownership lost; sync-control only");
    }

    private static MunicipalSyncResult result(
            MunicipalSyncRunStatus status,
            int received,
            int accepted,
            int rejected,
            int inserted,
            int updated,
            int unchanged,
            int occupancyInserted,
            int deactivated,
            int reactivated,
            int activeLinkCount,
            String category,
            String summary) {
        return new MunicipalSyncResult(
                status,
                received,
                accepted,
                rejected,
                inserted,
                updated,
                unchanged,
                occupancyInserted,
                deactivated,
                reactivated,
                activeLinkCount,
                category,
                summary);
    }

    private static String truncate(String message) {
        if (message == null) return null;
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }
}
