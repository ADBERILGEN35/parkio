package com.parkio.parking.application.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.MunicipalDistrictFacilityProjection;
import com.parkio.parking.application.port.MunicipalQualityReportQueryPort;
import com.parkio.parking.externalsource.district.MunicipalDistrictAssignmentPolicy;
import com.parkio.parking.externalsource.district.MunicipalDistrictAssetLoader;
import com.parkio.parking.externalsource.district.MunicipalDistrictCoveragePolicy;
import com.parkio.parking.externalsource.district.MunicipalDistrictGeometry;
import com.parkio.parking.externalsource.district.MunicipalDistrictTopologyPolicy;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.metrics.MunicipalDistrictCoverageMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Assembles the DATA-WP-18/19 district coverage section with asset load, assignment and TTL cache.
 * Read-only: never mutates facilities, links, provenance, occupancy or asset files.
 */
@Component
public class MunicipalDistrictCoverageAssembler {
    private static final Logger log = LoggerFactory.getLogger(MunicipalDistrictCoverageAssembler.class);

    private final MunicipalSourceProperties properties;
    private final MunicipalQualityReportQueryPort queries;
    private final MunicipalDistrictAssetLoader loader;
    private final MunicipalDistrictCoverageMetrics metrics;
    private final Clock clock;
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();
    private final AtomicReference<LoadedDistricts> loadedDistricts = new AtomicReference<>();

    @Autowired
    public MunicipalDistrictCoverageAssembler(
            MunicipalSourceProperties properties,
            MunicipalQualityReportQueryPort queries,
            ObjectMapper objectMapper,
            MunicipalDistrictCoverageMetrics metrics,
            Clock clock) {
        this.properties = properties;
        this.queries = queries;
        this.loader = new MunicipalDistrictAssetLoader(objectMapper);
        this.metrics = metrics;
        this.clock = clock;
    }

    MunicipalDistrictCoverageAssembler(
            MunicipalSourceProperties properties,
            MunicipalQualityReportQueryPort queries,
            MunicipalDistrictAssetLoader loader,
            MunicipalDistrictCoverageMetrics metrics,
            Clock clock) {
        this.properties = properties;
        this.queries = queries;
        this.loader = loader;
        this.metrics = metrics;
        this.clock = clock;
    }

    public DistrictCoverageSection assemble(Instant generatedAt, long agingSeconds, long staleSeconds) {
        long start = System.nanoTime();
        MunicipalSourceProperties.Ops ops = properties.getOps();
        if (!ops.isDistrictCoverageEnabled()) {
            cache.set(null);
            loadedDistricts.set(null);
            DistrictCoverageSection disabled = DistrictCoverageSection.disabled(generatedAt);
            metrics.recordRequest("disabled", "disabled", "none", elapsedMs(start), 0, 0);
            return disabled;
        }

        MunicipalSourceProperties.DistrictCoverage cfg = ops.getDistrictCoverage();
        String cacheKey = cacheKey(cfg);
        CacheEntry hit = cache.get();
        if (hit != null
                && Objects.equals(hit.cacheKey(), cacheKey)
                && !hit.expired(clock.instant(), cfg.getCacheTtlSeconds())) {
            metrics.recordRequest(
                    "cache_hit",
                    hit.section().status().name().toLowerCase(),
                    policyLabel(cfg),
                    elapsedMs(start),
                    hit.section().activeFacilityCountConsidered(),
                    hit.section().topologyAmbiguousCount() > 0
                            ? hit.section().topologyAmbiguousCount()
                            : hit.section().overlapAnomalyCount());
            return hit.section();
        }

        DistrictCoverageSection section = compute(generatedAt, ops, agingSeconds, staleSeconds);
        cache.set(new CacheEntry(clock.instant(), cacheKey, section));
        metrics.recordRequest(
                section.status() == DistrictCoverageStatus.AVAILABLE ? "success" : "unavailable",
                section.status().name().toLowerCase(),
                policyLabel(cfg),
                elapsedMs(start),
                section.activeFacilityCountConsidered(),
                section.topologyAmbiguousCount() > 0
                        ? section.topologyAmbiguousCount()
                        : section.overlapAnomalyCount());
        return section;
    }

    public void clearCache() {
        cache.set(null);
        loadedDistricts.set(null);
    }

    private DistrictCoverageSection compute(
            Instant generatedAt,
            MunicipalSourceProperties.Ops ops,
            long agingSeconds,
            long staleSeconds) {
        MunicipalSourceProperties.DistrictCoverage cfg = ops.getDistrictCoverage();
        LoadedDistricts districts = ensureDistricts(cfg);
        if (!districts.present()) {
            log.warn("municipal_district_coverage_asset_unavailable");
            return DistrictCoverageSection.unavailable(
                    generatedAt, MunicipalDistrictCoverageReason.ASSET_UNAVAILABLE);
        }
        if (!districts.valid()) {
            log.warn("municipal_district_coverage_asset_invalid");
            return DistrictCoverageSection.unavailable(
                    generatedAt,
                    cfg.isTopologyPolicyEnabled()
                            ? MunicipalDistrictCoverageReason.TOPOLOGY_INVALID
                            : MunicipalDistrictCoverageReason.ASSET_INVALID);
        }

        var projections = queries.listActiveFacilityProjections(
                cfg.getMaxFacilities(), agingSeconds, staleSeconds, generatedAt);
        if (projections.size() > cfg.getMaxFacilities()) {
            log.warn(
                    "municipal_district_coverage_facility_limit_exceeded max={}",
                    cfg.getMaxFacilities());
            return DistrictCoverageSection.unavailable(
                    generatedAt, MunicipalDistrictCoverageReason.FACILITY_LIMIT);
        }

        boolean topology = cfg.isTopologyPolicyEnabled();
        MunicipalDistrictAssignmentPolicy policy =
                new MunicipalDistrictAssignmentPolicy(districts.geometries(), topology);
        Map<String, Acc> byFolded = new LinkedHashMap<>();
        for (MunicipalDistrictGeometry g : districts.geometries()) {
            byFolded.put(g.foldedName(), new Acc(g.districtName()));
        }

        long assigned = 0;
        long unassigned = 0;
        long invalid = 0;
        long overlaps = 0;
        long boundaryAmbiguous = 0;
        long topologyAmbiguous = 0;
        for (MunicipalDistrictFacilityProjection row : projections) {
            var assignment = policy.assign(row.longitude(), row.latitude());
            switch (assignment.classification()) {
                case INVALID_COORDINATES -> invalid++;
                case UNASSIGNED -> unassigned++;
                case BOUNDARY_AMBIGUOUS -> boundaryAmbiguous++;
                case TOPOLOGY_AMBIGUOUS -> {
                    topologyAmbiguous++;
                    overlaps++;
                }
                case ASSIGNED -> {
                    assigned++;
                    if (assignment.overlapAnomaly()) {
                        overlaps++;
                    }
                    Acc acc = byFolded.get(assignment.foldedName());
                    if (acc != null) {
                        acc.total++;
                        if (row.osmLinked()) {
                            acc.osm++;
                            if (row.osmRealNameLabel()) {
                                acc.realName++;
                            }
                            if (row.osmNeutralFallbackLabel()) {
                                acc.neutral++;
                            }
                        }
                        if (row.izumLinked()) {
                            acc.izum++;
                            if (row.izumAvailabilityExposed()) {
                                acc.exposed++;
                            }
                        }
                        if (row.provenanceCovered()) {
                            acc.provenance++;
                        }
                    }
                }
            }
        }

        List<DistrictCoverageEntry> entries = byFolded.values().stream()
                .map(a -> new DistrictCoverageEntry(
                        a.name, a.total, a.osm, a.izum, a.exposed, a.realName, a.neutral, a.provenance))
                .toList();

        return new DistrictCoverageSection(
                DistrictCoverageStatus.AVAILABLE,
                null,
                MunicipalDistrictCoveragePolicy.POLICY_VERSION,
                topology
                        ? MunicipalDistrictTopologyPolicy.NORMALIZED_ASSET_VERSION
                        : MunicipalDistrictCoveragePolicy.ASSET_VERSION,
                generatedAt,
                entries.size(),
                projections.size(),
                assigned,
                unassigned,
                invalid,
                overlaps,
                entries,
                topology ? cfg.getTopologyPolicyVersion() : null,
                topology ? MunicipalDistrictTopologyPolicy.NORMALIZED_ASSET_VERSION : null,
                topology ? "AVAILABLE" : "DISABLED",
                boundaryAmbiguous,
                topologyAmbiguous);
    }

    private LoadedDistricts ensureDistricts(MunicipalSourceProperties.DistrictCoverage cfg) {
        String key = cacheKey(cfg);
        LoadedDistricts current = loadedDistricts.get();
        if (current != null && Objects.equals(current.loadKey(), key)) {
            return current;
        }
        synchronized (this) {
            current = loadedDistricts.get();
            if (current != null && Objects.equals(current.loadKey(), key)) {
                return current;
            }
            boolean topology = cfg.isTopologyPolicyEnabled();
            String pathText = topology ? cfg.getNormalizedAssetPath() : cfg.getAssetPath();
            String expectedSha = topology ? cfg.getNormalizedAssetSha256() : cfg.getExpectedSha256();
            if (pathText == null || pathText.isBlank()) {
                loadedDistricts.set(LoadedDistricts.missing(key));
                return loadedDistricts.get();
            }
            Path path = Path.of(pathText);
            if (!Files.isRegularFile(path)) {
                loadedDistricts.set(LoadedDistricts.missing(key));
                return loadedDistricts.get();
            }
            try {
                byte[] bytes = Files.readAllBytes(path);
                var loaded = loader.load(
                        bytes,
                        expectedSha,
                        cfg.getNameProperty(),
                        cfg.getExpectedCount(),
                        topology);
                LoadedDistricts next = loaded.valid()
                        ? LoadedDistricts.ok(key, loaded.districts())
                        : LoadedDistricts.invalid(key);
                loadedDistricts.set(next);
                return next;
            } catch (Exception ex) {
                log.warn("municipal_district_coverage_asset_load_failed");
                loadedDistricts.set(LoadedDistricts.missing(key));
                return loadedDistricts.get();
            }
        }
    }

    private static String cacheKey(MunicipalSourceProperties.DistrictCoverage cfg) {
        return (cfg.isTopologyPolicyEnabled() ? "topo:" : "legacy:")
                + cfg.getTopologyPolicyVersion()
                + "|"
                + (cfg.isTopologyPolicyEnabled()
                        ? cfg.getNormalizedAssetSha256()
                        : cfg.getExpectedSha256())
                + "|"
                + (cfg.isTopologyPolicyEnabled() ? cfg.getNormalizedAssetPath() : cfg.getAssetPath());
    }

    private static String policyLabel(MunicipalSourceProperties.DistrictCoverage cfg) {
        return cfg.isTopologyPolicyEnabled()
                ? cfg.getTopologyPolicyVersion()
                : MunicipalDistrictCoveragePolicy.POLICY_VERSION;
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private record CacheEntry(Instant loadedAt, String cacheKey, DistrictCoverageSection section) {
        boolean expired(Instant now, int ttlSeconds) {
            return now.isAfter(loadedAt.plusSeconds(ttlSeconds));
        }
    }

    private record LoadedDistricts(
            String loadKey, boolean present, boolean valid, List<MunicipalDistrictGeometry> geometries) {
        static LoadedDistricts missing(String key) {
            return new LoadedDistricts(key, false, false, List.of());
        }

        static LoadedDistricts invalid(String key) {
            return new LoadedDistricts(key, true, false, List.of());
        }

        static LoadedDistricts ok(String key, List<MunicipalDistrictGeometry> geometries) {
            return new LoadedDistricts(key, true, true, List.copyOf(geometries));
        }
    }

    private static final class Acc {
        final String name;
        long total;
        long osm;
        long izum;
        long exposed;
        long realName;
        long neutral;
        long provenance;

        Acc(String name) {
            this.name = name;
        }
    }
}
