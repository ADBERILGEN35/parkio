package com.parkio.parking.application.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.MunicipalSourceSlaPolicy;
import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalQualityReportQueryPort;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.registry.MunicipalQualityReportPolicy;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the operator municipal quality/coverage report (DATA-WP-15).
 *
 * <p>Read-only: never starts a sync, import, conflation or linking operation. Reports coverage
 * and freshness facts only — no aggregate quality score, trust score, linking readiness or
 * production-readiness verdict is derived here or downstream.
 */
@Service
@Transactional(readOnly = true)
public class MunicipalQualityReportService {
    private final MunicipalQualityReportQueryPort queries;
    private final MunicipalSourceHealthService healthService;
    private final MunicipalSourceProperties properties;
    private final MunicipalDataSourceRepository sources;
    private final MunicipalSourceSyncRunRepository runs;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public MunicipalQualityReportService(
            MunicipalQualityReportQueryPort queries,
            MunicipalSourceHealthService healthService,
            MunicipalSourceProperties properties,
            MunicipalDataSourceRepository sources,
            MunicipalSourceSyncRunRepository runs,
            Clock clock,
            ObjectMapper objectMapper) {
        this.queries = queries;
        this.healthService = healthService;
        this.properties = properties;
        this.sources = sources;
        this.runs = runs;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public MunicipalQualityReport overallReport() {
        Instant generatedAt = clock.instant();
        long activeFacilities = queries.countActiveFacilities();
        return new MunicipalQualityReport(
                MunicipalQualityReportPolicy.POLICY_VERSION,
                generatedAt,
                activeFacilities,
                List.of(
                        summary(MunicipalSourceIdentity.OSM, activeFacilities),
                        summary(MunicipalSourceIdentity.IZUM, activeFacilities)),
                osmSection(),
                izumSection(generatedAt),
                integrity());
    }

    public SourceQualityDetail sourceReport(String sourceKey, Integer limit) {
        if (!MunicipalQualityReportPolicy.isSupported(sourceKey)) {
            throw new UnknownQualityReportSourceException("unsupported source key: " + sourceKey);
        }
        int resolvedLimit = resolveLimit(limit);
        Instant generatedAt = clock.instant();
        long activeFacilities = queries.countActiveFacilities();
        boolean osm = MunicipalSourceIdentity.isOsm(sourceKey);
        return new SourceQualityDetail(
                MunicipalQualityReportPolicy.POLICY_VERSION,
                generatedAt,
                summary(sourceKey, activeFacilities),
                osm ? osmSection() : null,
                osm ? null : izumSection(generatedAt),
                resolvedLimit,
                recentRuns(sourceKey, resolvedLimit));
    }

    private int resolveLimit(Integer limit) {
        MunicipalSourceProperties.Ops ops = properties.getOps();
        if (limit == null) {
            return ops.getRecentRunLimitDefault();
        }
        int max = ops.getRecentRunLimitMax();
        if (limit < 1 || limit > max) {
            throw new IllegalArgumentException("limit must be between 1 and " + max);
        }
        return limit;
    }

    private SourceQualitySummary summary(String sourceKey, long totalActiveFacilities) {
        boolean osm = MunicipalSourceIdentity.isOsm(sourceKey);
        // OSM stays "on" for ops when publication or import is enabled (hosted-beta leave-on
        // keeps import=false while publication=true). İZUM uses the source enable flag.
        boolean sourceEnabled = osm
                ? (properties.getOsm().isImportEnabled() || properties.getOsm().isPublicationEnabled())
                : properties.getIzum().isEnabled();
        boolean schedulerEnabled = osm
                ? properties.getOsm().isSchedulerEnabled()
                : properties.getIzum().isSchedulerEnabled();
        boolean publicationEnabled = osm
                ? properties.getOsm().isPublicationEnabled()
                : true;

        MunicipalSourceHealthService.Snapshot snapshot =
                healthService.snapshot(sourceKey, sourceEnabled, schedulerEnabled);
        MunicipalSourceSlaPolicy.Evaluation evaluation = snapshot.evaluation();

        long activeFacilities = queries.countActiveFacilitiesBySourceKey(sourceKey);
        return new SourceQualitySummary(
                sourceKey,
                MunicipalSourceIdentity.familyOf(sourceKey),
                snapshot.operatingMode().name(),
                snapshot.municipalEnabled(),
                snapshot.sourceEnabled(),
                snapshot.schedulerEnabled(),
                publicationEnabled,
                snapshot.operationalState().name(),
                evaluation.lastRunStatus(),
                evaluation.lastRunAt(),
                evaluation.lastSuccessAt(),
                evaluation.secondsSinceSuccess(),
                evaluation.consecutiveFailures(),
                evaluation.failuresInWindow(),
                evaluation.staleRunningOperations(),
                evaluation.lastFailureCategory(),
                snapshot.occupancyFreshness().name(),
                activeFacilities,
                queries.countActiveLinksBySourceKey(sourceKey),
                CoverageMetric.of(activeFacilities, totalActiveFacilities),
                provenanceCoverage(sourceKey, activeFacilities));
    }

    private List<ProvenanceFieldCoverage> provenanceCoverage(String sourceKey, long activeFacilities) {
        Map<String, Long> covered = new LinkedHashMap<>();
        for (MunicipalQualityReportQueryPort.FieldCoverage row : queries.provenanceCoverageBySource(sourceKey)) {
            if (MunicipalQualityReportPolicy.ALLOWED_PROVENANCE_FIELDS.contains(row.fieldName())) {
                covered.merge(row.fieldName(), row.covered(), Long::sum);
            }
        }
        List<ProvenanceFieldCoverage> rows = new ArrayList<>();
        for (String field : MunicipalQualityReportPolicy.PROVENANCE_FIELD_ORDER) {
            rows.add(ProvenanceFieldCoverage.of(field, covered.getOrDefault(field, 0L), activeFacilities));
        }
        return List.copyOf(rows);
    }

    private OsmQualitySection osmSection() {
        long activeFacilities = queries.countActiveFacilitiesBySourceKey(MunicipalSourceIdentity.OSM);
        Map<String, Long> outcomes = labelOutcomes();
        long nameBearing = MunicipalQualityReportPolicy.NAME_BEARING_OUTCOMES.stream()
                .mapToLong(outcome -> outcomes.getOrDefault(outcome, 0L))
                .sum();
        return new OsmQualitySection(
                properties.getOsm().isImportEnabled(),
                properties.getOsm().isSchedulerEnabled(),
                properties.getOsm().isPublicationEnabled(),
                properties.getOsm().getClipVersion(),
                properties.getOsm().getLabelPolicy(),
                activeFacilities,
                CoverageMetric.of(nameBearing, activeFacilities),
                queries.technicalLabelCount(),
                queries.staleNameMismatchCount(),
                queries.countOsmOccupancy(),
                CoverageMetric.of(queries.countOsmNullAvailability(), activeFacilities),
                outcomes,
                normalizeImportReport(queries.latestOsmImportQualityReportJson()));
    }

    private Map<String, Long> labelOutcomes() {
        Map<String, Long> outcomes = new LinkedHashMap<>();
        long unknown = 0;
        for (MunicipalQualityReportQueryPort.LabelOutcomeCount row : queries.labelOutcomeHistogram()) {
            if (row.outcome() != null
                    && MunicipalQualityReportPolicy.KNOWN_LABEL_OUTCOMES.contains(row.outcome())) {
                outcomes.merge(row.outcome(), row.total(), Long::sum);
            } else {
                unknown += row.total();
            }
        }
        if (unknown > 0) {
            outcomes.put("unknown", unknown);
        }
        return Map.copyOf(outcomes);
    }

    private IzumQualitySection izumSection(Instant now) {
        Optional<MunicipalDataSourceRepository.Source> source =
                sources.findBySourceKey(MunicipalSourceIdentity.IZUM);
        long aging = source.map(MunicipalDataSourceRepository.Source::agingAfterSeconds).orElse(0L);
        long stale = source.map(MunicipalDataSourceRepository.Source::staleAfterSeconds).orElse(0L);
        long activeFacilities = queries.countActiveFacilitiesBySourceKey(MunicipalSourceIdentity.IZUM);
        MunicipalQualityReportQueryPort.IzumFreshness freshness =
                queries.countIzumFreshnessBuckets(aging, stale, now);
        return new IzumQualitySection(
                properties.getIzum().isEnabled(),
                properties.getIzum().isSchedulerEnabled(),
                aging,
                stale,
                activeFacilities,
                freshness.total(),
                CoverageMetric.of(freshness.live(), activeFacilities),
                CoverageMetric.of(freshness.aging(), activeFacilities),
                CoverageMetric.of(freshness.stale(), activeFacilities),
                CoverageMetric.of(freshness.availabilityExposed(), activeFacilities));
    }

    private IntegrityGuardrails integrity() {
        MunicipalQualityReportQueryPort.IntegrityCounts counts = queries.integrityCounts();
        return new IntegrityGuardrails(
                counts.duplicateSourceLinkGroups(),
                counts.duplicateProvenanceGroups(),
                counts.linkCandidates(),
                counts.pendingLinkCandidates(),
                counts.linkReviewDecisions(),
                counts.facilityAliases(),
                counts.tariffPlans(),
                counts.activeTariffAssignments(),
                counts.izelmanLinkedActiveFacilities(),
                counts.osmOccupancySnapshots());
    }

    private List<RecentSyncRunSummary> recentRuns(String sourceKey, int limit) {
        Optional<UUID> sourceId = sources.findBySourceKey(sourceKey)
                .map(MunicipalDataSourceRepository.Source::id);
        if (sourceId.isEmpty()) {
            return List.of();
        }
        return runs.findRecentCompleted(sourceId.get(), limit).stream()
                .map(run -> new RecentSyncRunSummary(
                        run.status(), run.errorCategory(), run.startedAt(), run.completedAt()))
                .toList();
    }

    /** Copies only allow-listed keys; unknown keys, oversized maps and unknown outcomes are dropped. */
    private NormalizedQualityReport normalizeImportReport(Optional<String> json) {
        if (json.isEmpty() || json.get().isBlank()) {
            return NormalizedQualityReport.empty();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json.get());
        } catch (Exception unparseable) {
            return NormalizedQualityReport.empty();
        }
        if (root == null || !root.isObject()) {
            return NormalizedQualityReport.empty();
        }
        return new NormalizedQualityReport(
                true,
                readLong(root, "named"),
                readLong(root, "unnamed"),
                readLong(root, "capacityKnown"),
                readText(root, "clipVersion"),
                readText(root, "labelPolicyVersion"),
                readCounts(root, "rejectReasons", MunicipalQualityReportPolicy.MAX_REJECT_REASON_KEYS, false),
                readCounts(root, "labelOutcomes", MunicipalQualityReportPolicy.KNOWN_LABEL_OUTCOMES.size(), true));
    }

    private static Long readLong(JsonNode root, String key) {
        JsonNode node = allowListed(root, key);
        return node != null && node.isNumber() ? node.asLong() : null;
    }

    private static String readText(JsonNode root, String key) {
        JsonNode node = allowListed(root, key);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.length() > MunicipalQualityReportPolicy.MAX_TEXT_LENGTH
                ? value.substring(0, MunicipalQualityReportPolicy.MAX_TEXT_LENGTH)
                : value;
    }

    private static Map<String, Long> readCounts(
            JsonNode root, String key, int maxKeys, boolean knownOutcomesOnly) {
        JsonNode node = allowListed(root, key);
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext() && counts.size() < maxKeys) {
            String name = names.next();
            if (knownOutcomesOnly
                    && !MunicipalQualityReportPolicy.KNOWN_LABEL_OUTCOMES.contains(name)) {
                continue;
            }
            JsonNode value = node.get(name);
            if (value == null || !value.isNumber()) {
                continue;
            }
            String boundedName = name.length() > MunicipalQualityReportPolicy.MAX_TEXT_LENGTH
                    ? name.substring(0, MunicipalQualityReportPolicy.MAX_TEXT_LENGTH)
                    : name;
            counts.put(boundedName, value.asLong());
        }
        return Map.copyOf(counts);
    }

    private static JsonNode allowListed(JsonNode root, String key) {
        if (!MunicipalQualityReportPolicy.ALLOWED_QUALITY_REPORT_KEYS.contains(key)) {
            return null;
        }
        return root.get(key);
    }
}
