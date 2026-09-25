package com.parkio.parking.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-only aggregate queries backing the operator quality/coverage report (DATA-WP-15).
 * Every method returns counts only; no facility rows, raw payloads or hashes leave the adapter.
 */
public interface MunicipalQualityReportQueryPort {
    record LabelOutcomeCount(String outcome, long total) {}

    record FieldCoverage(String fieldName, long covered) {}

    record IntegrityCounts(
            long duplicateSourceLinkGroups,
            long duplicateProvenanceGroups,
            long linkCandidates,
            long pendingLinkCandidates,
            long linkReviewDecisions,
            long facilityAliases,
            long tariffPlans,
            long activeTariffAssignments,
            long izelmanLinkedActiveFacilities,
            long osmOccupancySnapshots) {}

    /** Latest-snapshot-per-facility freshness classification plus exposed-availability count. */
    record IzumFreshness(long live, long aging, long stale, long availabilityExposed, long total) {}

    long countActiveFacilities();

    long countActiveFacilitiesBySourceKey(String sourceKey);

    long countActiveLinksBySourceKey(String sourceKey);

    /** OSM never publishes occupancy; a non-zero result is an integrity violation. */
    long countOsmOccupancy();

    /** Active OSM facilities without any OSM occupancy row carrying availability. */
    long countOsmNullAvailability();

    List<LabelOutcomeCount> labelOutcomeHistogram();

    long technicalLabelCount();

    List<FieldCoverage> provenanceCoverageBySource(String sourceKey);

    long staleNameMismatchCount();

    IntegrityCounts integrityCounts();

    Optional<String> latestOsmImportQualityReportJson();

    IzumFreshness countIzumFreshnessBuckets(long agingSeconds, long staleSeconds, Instant now);

    /**
     * Bounded active-facility projection for district assignment. Returns at most
     * {@code maxFacilities + 1} rows so callers can detect hard-limit overflow without
     * truncating silently.
     */
    java.util.List<MunicipalDistrictFacilityProjection> listActiveFacilityProjections(
            int maxFacilities, long agingSeconds, long staleSeconds, Instant now);
}
