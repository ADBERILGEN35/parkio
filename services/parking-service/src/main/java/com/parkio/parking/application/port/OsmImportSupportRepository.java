package com.parkio.parking.application.port;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.osm.ConflationDecision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OsmImportSupportRepository {
    record MunicipalCandidate(
            UUID facilityId,
            String externalId,
            String displayName,
            String operatorName,
            MunicipalFacilityType facilityType,
            MunicipalAccessClassification access,
            Integer capacity,
            double latitude,
            double longitude) {}

    record ExistingDecision(
            UUID id,
            UUID facilityAId,
            UUID facilityBId,
            String externalIdA,
            String externalIdB,
            ConflationDecision decision) {}

    record ImportRunStats(
            int elementsRead, int extracted, int rejected, int inserted, int updated, int unchanged,
            int deactivated, int reactivated, int conflationCandidates, int autoMatched,
            int reviewRequired, int rejectedMatches, int hardConflicts, boolean completeSuccess,
            String qualityReportJson) {}

    Set<String> activeExternalIds(UUID sourceId);

    default int deactivateMissing(UUID sourceId, Set<String> seenExternalIds, Instant now) {
        return deactivateMissing(sourceId, seenExternalIds, now, false);
    }

    /**
     * Soft-deactivate active source links whose external id is absent from {@code seenExternalIds}.
     * When {@code seenExternalIds} is empty, deactivation runs only if {@code trustedAuthoritativeEmptySet}
     * is true (caller already proved a trustworthy authoritative snapshot with an intentionally empty active set).
     */
    int deactivateMissing(
            UUID sourceId, Set<String> seenExternalIds, Instant now, boolean trustedAuthoritativeEmptySet);

    int reactivate(UUID sourceId, String externalId, Instant now);

    List<MunicipalCandidate> findMunicipalCandidatesNear(double lat, double lng, double radiusMeters);

    Optional<ExistingDecision> findActiveDecision(UUID facilityA, UUID facilityB);

    Optional<ExistingDecision> findActiveDecisionByExternalPair(
            String sourceKeyA, String externalA, String sourceKeyB, String externalB);

    void insertDecision(
            UUID facilityA, UUID facilityB,
            String sourceKeyA, String sourceKeyB,
            String externalA, String externalB,
            ConflationDecision decision, String reason, String policyVersion,
            String signalJson, Double score, boolean automatic, String actor, Instant now);

    void reassignOsmLinkToFacility(UUID sourceId, String osmExternalId, UUID targetFacilityId, Instant now);

    void softDeactivateFacility(UUID facilityId, Instant now);

    void saveImportRun(
            UUID id, UUID syncRunId, String filename, String sourceUrl, Instant downloadedAt,
            Long fileSize, String sha256, String configVersion, String clipVersion, boolean dryRun,
            ImportRunStats stats, Instant now);
}