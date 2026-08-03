package com.parkio.parking.application.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.MunicipalDistrictFacilityProjection;
import com.parkio.parking.application.port.MunicipalQualityReportQueryPort;
import com.parkio.parking.externalsource.district.MunicipalDistrictAssignmentPolicy;
import com.parkio.parking.externalsource.district.MunicipalDistrictAssetLoader;
import com.parkio.parking.externalsource.district.MunicipalDistrictCoveragePolicy;
import com.parkio.parking.externalsource.district.MunicipalDistrictGeometry;
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
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Assembles the DATA-WP-18 district coverage section with asset load, assignment and TTL cache.
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

    /** Test/constructor seam allowing a custom loader. */
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
            DistrictCoverageSection disabled = DistrictCoverageSection.disabled(generatedAt);
            metrics.recordRequest("disabled", "disabled", "none", elapsedMs(start), 0, 0);
            return disabled;
        }

        CacheEntry hit = cache.get();
        if (hit != null && !hit.expired(clock.instant(), ops.getDistrictCoverage().getCacheTtlSeconds())) {
            metrics.recordRequest(
                    "cache_hit",
                    hit.section().status().name().toLowerCase(),
                    MunicipalDistrictCoveragePolicy.POLICY_VERSION,
                    elapsedMs(start),
                    hit.section().activeFacilityCountConsidered(),
                    hit.section().overlapAnomalyCount());
            return hit.section();
        }

        DistrictCoverageSection section = compute(generatedAt, ops, agingSeconds, staleSeconds);
        cache.set(new CacheEntry(clock.instant(), section));
        metrics.recordRequest(
                section.status() == DistrictCoverageStatus.AVAILABLE ? "success" : "unavailable",
                section.status().name().toLowerCase(),
                MunicipalDistrictCoveragePolicy.POLICY_VERSION,
                elapsedMs(start),
                section.activeFacilityCountConsidered(),
                section.overlapAnomalyCount());
        return section;
    }

    /** Clears report and district caches (tests / optional reload). */
    public void clearCache() {
        cache.set(null);
        loadedDistricts.set(null);
    }

    private DistrictCoverageSection compute(
            Instant generatedAt,
            MunicipalSourceProperties.Ops ops,
            long agingSeconds,
            long staleSeconds) {
        LoadedDistricts districts = ensureDistricts(ops.getDistrictCoverage());
        if (!districts.present()) {
            log.warn("municipal_district_coverage_asset_unavailable");
            return DistrictCoverageSection.unavailable(
                    generatedAt, MunicipalDistrictCoverageReason.ASSET_UNAVAILABLE);
        }
        if (!districts.valid()) {
            log.warn("municipal_district_coverage_asset_invalid");
            return DistrictCoverageSection.unavailable(
                    generatedAt, MunicipalDistrictCoverageReason.ASSET_INVALID);
        }

        MunicipalSourceProperties.DistrictCoverage cfg = ops.getDistrictCoverage();
        var projections = queries.listActiveFacilityProjections(
                cfg.getMaxFacilities(), agingSeconds, staleSeconds, generatedAt);
        if (projections.size() > cfg.getMaxFacilities()) {
            log.warn(
                    "municipal_district_coverage_facility_limit_exceeded max={}",
                    cfg.getMaxFacilities());
            return DistrictCoverageSection.unavailable(
                    generatedAt, MunicipalDistrictCoverageReason.FACILITY_LIMIT);
        }

        MunicipalDistrictAssignmentPolicy policy =
                new MunicipalDistrictAssignmentPolicy(districts.geometries());
        Map<String, Acc> byFolded = new LinkedHashMap<>();
        for (MunicipalDistrictGeometry g : districts.geometries()) {
            byFolded.put(g.foldedName(), new Acc(g.districtName()));
        }

        long assigned = 0;
        long unassigned = 0;
        long invalid = 0;
        long overlaps = 0;
        for (MunicipalDistrictFacilityProjection row : projections) {
            var assignment = policy.assign(row.longitude(), row.latitude());
            switch (assignment.classification()) {
                case INVALID_COORDINATES -> invalid++;
                case UNASSIGNED -> unassigned++;
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
                MunicipalDistrictCoveragePolicy.ASSET_VERSION,
                generatedAt,
                entries.size(),
                projections.size(),
                assigned,
                unassigned,
                invalid,
                overlaps,
                entries);
    }

    private LoadedDistricts ensureDistricts(MunicipalSourceProperties.DistrictCoverage cfg) {
        LoadedDistricts current = loadedDistricts.get();
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = loadedDistricts.get();
            if (current != null) {
                return current;
            }
            String pathText = cfg.getAssetPath();
            if (pathText == null || pathText.isBlank()) {
                loadedDistricts.set(LoadedDistricts.missing());
                return loadedDistricts.get();
            }
            Path path = Path.of(pathText);
            if (!Files.isRegularFile(path)) {
                loadedDistricts.set(LoadedDistricts.missing());
                return loadedDistricts.get();
            }
            try {
                var loaded = loader.load(
                        path, cfg.getExpectedSha256(), cfg.getNameProperty(), cfg.getExpectedCount());
                LoadedDistricts next = loaded.valid()
                        ? LoadedDistricts.ok(loaded.districts())
                        : LoadedDistricts.invalid();
                loadedDistricts.set(next);
                return next;
            } catch (Exception ex) {
                log.warn("municipal_district_coverage_asset_load_failed");
                loadedDistricts.set(LoadedDistricts.missing());
                return loadedDistricts.get();
            }
        }
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private record CacheEntry(Instant loadedAt, DistrictCoverageSection section) {
        boolean expired(Instant now, int ttlSeconds) {
            return now.isAfter(loadedAt.plusSeconds(ttlSeconds));
        }
    }

    private record LoadedDistricts(boolean present, boolean valid, List<MunicipalDistrictGeometry> geometries) {
        static LoadedDistricts missing() {
            return new LoadedDistricts(false, false, List.of());
        }

        static LoadedDistricts invalid() {
            return new LoadedDistricts(true, false, List.of());
        }

        static LoadedDistricts ok(List<MunicipalDistrictGeometry> geometries) {
            return new LoadedDistricts(true, true, List.copyOf(geometries));
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
