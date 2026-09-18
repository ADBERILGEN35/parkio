package com.parkio.parking.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.application.port.OsmImportSupportRepository;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.osm.ConflationDecision;
import com.parkio.parking.externalsource.osm.ConflationPolicy;
import com.parkio.parking.externalsource.osm.IzmirClip;
import com.parkio.parking.externalsource.osm.OsmAccessMapper;
import com.parkio.parking.externalsource.osm.OsmDisplayLabelPolicy;
import com.parkio.parking.externalsource.osm.OsmDisplayLabelSelection;
import com.parkio.parking.externalsource.osm.OsmGeoJsonParkingParser;
import com.parkio.parking.externalsource.osm.OsmParkingFeature;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.metrics.OsmDisplayLabelMetrics;
import com.parkio.parking.infrastructure.osm.OsmGeofabrikSourceKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OsmImportApplicationService {
    private static final Logger log = LoggerFactory.getLogger(OsmImportApplicationService.class);

    private final MunicipalSourceProperties properties;
    private final MunicipalDataSourceRepository sources;
    private final MunicipalSourceSyncRunRepository runs;
    private final OsmImportSupportRepository support;
    private final OsmGeoJsonParkingParser parser;
    private final ObjectMapper objectMapper;
    private final MunicipalFacilityIngestWriter ingestWriter;
    private final OsmDisplayLabelMetrics labelMetrics;
    private final Clock clock;

    public OsmImportApplicationService(
            MunicipalSourceProperties properties,
            MunicipalDataSourceRepository sources,
            MunicipalSourceSyncRunRepository runs,
            OsmImportSupportRepository support,
            OsmGeoJsonParkingParser parser,
            ObjectMapper objectMapper,
            MunicipalFacilityIngestWriter ingestWriter,
            OsmDisplayLabelMetrics labelMetrics,
            Clock clock) {
        this.properties = properties;
        this.sources = sources;
        this.runs = runs;
        this.support = support;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.ingestWriter = ingestWriter;
        this.labelMetrics = labelMetrics;
        this.clock = clock;
    }

    @Transactional
    public OsmImportResult importFromConfiguredPath(boolean dryRun) {
        MunicipalSourceProperties.Osm osm = properties.getOsm();
        if (!properties.isEnabled() || !osm.isImportEnabled()) {
            throw new IllegalStateException("OSM import is disabled");
        }
        if (osm.getLocalInputPath() == null || osm.getLocalInputPath().isBlank()) {
            throw new IllegalArgumentException("parkio.municipal.osm.local-input-path is required");
        }
        Path path = Path.of(osm.getLocalInputPath()).toAbsolutePath().normalize();
        validatePath(path, osm);
        return importPath(path, dryRun);
    }

    @Transactional
    public OsmImportResult importPath(Path path, boolean dryRun) {
        Instant started = clock.instant();
        var source = sources.requireBySourceKey(OsmGeofabrikSourceKeys.SOURCE_KEY);
        Optional<UUID> runId = runs.tryStart(source.id(), UUID.randomUUID().toString(), started);
        if (runId.isEmpty()) {
            log.info("osm_import_skipped sourceKey={} reason=concurrent_run", OsmGeofabrikSourceKeys.SOURCE_KEY);
            return empty(MunicipalSyncRunStatus.SKIPPED, dryRun, path.getFileName().toString(), null, "concurrent_run", null);
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            long max = properties.getOsm().getMaxInputBytes();
            if (bytes.length > max) {
                throw new IllegalArgumentException("input exceeds max size");
            }
            String sha = sha256(bytes);
            String clipVersion = resolveClipVersion();
            log.info("osm_import_start sourceKey={} file={} bytes={} sha256={} dryRun={} clip={}",
                    OsmGeofabrikSourceKeys.SOURCE_KEY, path.getFileName(), bytes.length, sha, dryRun, clipVersion);

            List<OsmParkingFeature> features = parser.parse(bytes);
            int rejected = 0;
            int inserted = 0;
            int updated = 0;
            int unchanged = 0;
            int reactivated = 0;
            int candidates = 0;
            int autoMatched = 0;
            int reviewRequired = 0;
            int rejectedMatches = 0;
            int hardConflicts = 0;
            Set<String> seen = new HashSet<>();
            Map<String, Integer> rejectReasons = new HashMap<>();
            Map<String, Integer> labelOutcomes = new LinkedHashMap<>();
            int named = 0;
            int capacityKnown = 0;
            String labelPolicy = OsmDisplayLabelPolicy.normalizePolicyVersion(properties.getOsm().getLabelPolicy());

            for (OsmParkingFeature feature : features) {
                if (!feature.valid()) {
                    rejected++;
                    rejectReasons.merge(feature.rejectReason(), 1, Integer::sum);
                    continue;
                }
                if (!OsmAccessMapper.publishable(feature.access())
                        && !properties.getOsm().isPublishRestricted()) {
                    rejected++;
                    rejectReasons.merge("access_not_publishable", 1, Integer::sum);
                    continue;
                }
                seen.add(feature.externalId());
                OsmDisplayLabelSelection label = OsmDisplayLabelPolicy.select(
                        labelPolicy, feature.externalId(), feature.allowlistedTags());
                labelOutcomes.merge(label.outcome().metricOutcome(), 1, Integer::sum);
                if (label.nameBearing()) {
                    named++;
                }
                if (feature.capacity() != null) {
                    capacityKnown++;
                }
                if (dryRun) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("osmType", feature.elementType().wire());
                metadata.put("osmId", feature.osmId());
                metadata.put("geometryType", feature.geometryType());
                metadata.put("tags", feature.allowlistedTags());
                metadata.put("brand", feature.brand());
                metadata.put("fee", feature.fee());
                metadata.put("openingHours", feature.openingHours());
                metadata.put("clipVersion", clipVersion);
                metadata.put("labelPolicyVersion", label.policyVersion());
                metadata.put("labelOutcome", label.outcome().metricOutcome());
                metadata.put("attribution", OsmGeofabrikSourceKeys.ATTRIBUTION);
                NormalizedMunicipalFacility normalized = new NormalizedMunicipalFacility(
                        feature.externalId(),
                        feature.operator(),
                        feature.facilityType(),
                        label.displayLabel(),
                        null,
                        feature.latitude(),
                        feature.longitude(),
                        feature.capacity(),
                        feature.access(),
                        metadata,
                        feature.rawRecordHash());
                var upserted = ingestWriter.persistOsmFacility(
                        source.id(), normalized, label.nameBearing(), started);
                labelMetrics.record(label);
                if (upserted.inserted()) {
                    inserted++;
                } else if (upserted.changed()) {
                    updated++;
                } else {
                    unchanged++;
                    labelMetrics.recordUnchanged(labelPolicy);
                }

                if (properties.getOsm().isConflationEnabled()) {
                    ConflationOutcome outcome = conflate(feature, upserted.facilityId(), dryRun, started);
                    candidates += outcome.candidate() ? 1 : 0;
                    autoMatched += outcome.auto() ? 1 : 0;
                    reviewRequired += outcome.review() ? 1 : 0;
                    rejectedMatches += outcome.rejected() ? 1 : 0;
                    hardConflicts += outcome.hard() ? 1 : 0;
                }
            }

            int deactivated = 0;
            boolean completeSuccess = !dryRun;
            if (!dryRun && completeSuccess) {
                if (!runs.isRunning(runId.get())) {
                    log.warn("osm_import_ownership_lost sourceKey={} runId={} phase=before_reconcile",
                            OsmGeofabrikSourceKeys.SOURCE_KEY, runId.get());
                    return empty(MunicipalSyncRunStatus.FAILED, dryRun, path.getFileName().toString(), null,
                            "ownership_lost", "run ownership lost");
                }
                deactivated = support.deactivateMissing(source.id(), seen, started);
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("named", named);
            report.put("unnamed", Math.max(0, seen.size() - named));
            report.put("capacityKnown", capacityKnown);
            report.put("rejectReasons", rejectReasons);
            report.put("clipVersion", clipVersion);
            report.put("labelPolicyVersion", labelPolicy);
            report.put("labelOutcomes", labelOutcomes);
            String reportJson = objectMapper.writeValueAsString(report);

            OsmImportSupportRepository.ImportRunStats stats = new OsmImportSupportRepository.ImportRunStats(
                    features.size(), seen.size(), rejected, inserted, updated, unchanged, deactivated, reactivated,
                    candidates, autoMatched, reviewRequired, rejectedMatches, hardConflicts, completeSuccess, reportJson);

            MunicipalSyncResult syncResult = new MunicipalSyncResult(
                    MunicipalSyncRunStatus.SUCCESS,
                    features.size(), seen.size(), rejected, inserted, updated, unchanged, 0, null, null);
            if (!runs.complete(runId.get(), clock.instant(), syncResult, null, sha)) {
                log.warn("osm_import_complete_ignored sourceKey={} runId={} reason=ownership_lost",
                        OsmGeofabrikSourceKeys.SOURCE_KEY, runId.get());
                return empty(MunicipalSyncRunStatus.FAILED, dryRun, path.getFileName().toString(), sha,
                        "ownership_lost", "run ownership lost");
            }
            if (!dryRun) {
                sources.markSuccessful(source.id(), clock.instant());
            }
            support.saveImportRun(
                    UUID.randomUUID(), runId.get(), path.getFileName().toString(),
                    OsmGeofabrikSourceKeys.CANONICAL_URL, started, (long) bytes.length, sha,
                    OsmGeofabrikSourceKeys.IMPORT_CONFIG_VERSION, clipVersion, dryRun, stats, clock.instant());

            log.info("osm_import_complete sourceKey={} extracted={} rejected={} inserted={} updated={} deactivated={} autoMatched={} review={}",
                    OsmGeofabrikSourceKeys.SOURCE_KEY, seen.size(), rejected, inserted, updated, deactivated, autoMatched, reviewRequired);

            return new OsmImportResult(
                    MunicipalSyncRunStatus.SUCCESS, dryRun, path.getFileName().toString(), sha, clipVersion,
                    features.size(), seen.size(), rejected, inserted, updated, unchanged, deactivated, reactivated,
                    candidates, autoMatched, reviewRequired, rejectedMatches, hardConflicts, null, null, reportJson);
        } catch (Exception ex) {
            MunicipalSyncResult failed = new MunicipalSyncResult(
                    MunicipalSyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, 0, category(ex), truncate(ex.getMessage()));
            if (!runs.complete(runId.get(), clock.instant(), failed, null, null)) {
                log.warn("osm_import_complete_ignored sourceKey={} runId={} reason=ownership_lost",
                        OsmGeofabrikSourceKeys.SOURCE_KEY, runId.get());
            } else {
                log.warn("osm_import_failed sourceKey={} runId={} category={} summary={}",
                        OsmGeofabrikSourceKeys.SOURCE_KEY, runId.get(), failed.errorCategory(), failed.errorSummary());
            }
            return empty(MunicipalSyncRunStatus.FAILED, dryRun, path.getFileName().toString(), null,
                    failed.errorCategory(), failed.errorSummary());
        }
    }

    private ConflationOutcome conflate(OsmParkingFeature feature, UUID osmFacilityId, boolean dryRun, Instant now)
            throws Exception {
        // Prefer spatial candidates against municipal IZUM facilities.

        List<OsmImportSupportRepository.MunicipalCandidate> near =
                support.findMunicipalCandidatesNear(feature.latitude(), feature.longitude(),
                        ConflationPolicy.CANDIDATE_RADIUS_METERS);
        if (near.isEmpty()) {
            return ConflationOutcome.none();
        }
        boolean anyCandidate = false;
        boolean auto = false;
        boolean review = false;
        boolean rejected = false;
        boolean hard = false;
        boolean unnamed = feature.name() == null || feature.name().isBlank();
        for (var candidateFacility : near) {
            anyCandidate = true;
            Optional<OsmImportSupportRepository.ExistingDecision> existing =
                    support.findActiveDecision(osmFacilityId, candidateFacility.facilityId());
            if (existing.isEmpty()) {
                existing = support.findActiveDecisionByExternalPair(
                        OsmGeofabrikSourceKeys.SOURCE_KEY, feature.externalId(),
                        IzumMunicipalParkingAdapter.SOURCE_KEY, candidateFacility.externalId());
            }
            if (existing.isPresent()) {
                ConflationDecision prior = existing.get().decision();
                if (prior == ConflationDecision.MANUALLY_REJECTED) {
                    rejected = true;
                    continue;
                }
                if (prior == ConflationDecision.MANUALLY_MATCHED || prior == ConflationDecision.AUTO_MATCHED) {
                    if (!dryRun) {
                        support.reassignOsmLinkToFacility(
                                sources.requireBySourceKey(OsmGeofabrikSourceKeys.SOURCE_KEY).id(),
                                feature.externalId(), candidateFacility.facilityId(), now);
                    }
                    auto = prior == ConflationDecision.AUTO_MATCHED || prior == ConflationDecision.MANUALLY_MATCHED;
                    continue;
                }
                if (prior == ConflationDecision.REVIEW_REQUIRED) {
                    review = true;
                    continue;
                }
            }
            var evaluated = ConflationPolicy.evaluate(
                    feature.externalId(), feature.name(), feature.operator(), feature.facilityType(), feature.access(),
                    feature.capacity(), feature.latitude(), feature.longitude(),
                    candidateFacility.externalId(), candidateFacility.displayName(), candidateFacility.operatorName(),
                    candidateFacility.facilityType(), candidateFacility.access(), candidateFacility.capacity(),
                    candidateFacility.latitude(), candidateFacility.longitude());
            ConflationDecision decision = ConflationPolicy.decide(evaluated, unnamed);
            if (evaluated.hardConflict()) {
                hard = true;
            }
            if (decision == ConflationDecision.NOT_MATCHED) {
                continue;
            }
            if (decision == ConflationDecision.AUTO_MATCHED) {
                auto = true;
            } else if (decision == ConflationDecision.REVIEW_REQUIRED) {
                review = true;
            } else if (decision == ConflationDecision.REJECTED) {
                rejected = true;
            }
            if (!dryRun && existing.isEmpty()) {
                support.insertDecision(
                        osmFacilityId, candidateFacility.facilityId(),
                        OsmGeofabrikSourceKeys.SOURCE_KEY, IzumMunicipalParkingAdapter.SOURCE_KEY,
                        feature.externalId(), candidateFacility.externalId(),
                        decision,
                        decision == ConflationDecision.REJECTED
                                ? String.valueOf(evaluated.hardConflictReason())
                                : decision.name().toLowerCase(),
                        ConflationPolicy.POLICY_VERSION,
                        objectMapper.writeValueAsString(ConflationPolicy.signals(evaluated)),
                        evaluated.score(), true, "system", now);
                if (decision == ConflationDecision.AUTO_MATCHED
                        && properties.getOsm().isAutoMatchEnabled()) {
                    support.reassignOsmLinkToFacility(
                            sources.requireBySourceKey(OsmGeofabrikSourceKeys.SOURCE_KEY).id(),
                            feature.externalId(), candidateFacility.facilityId(), now);
                }
            }
        }
        return new ConflationOutcome(anyCandidate, auto, review, rejected, hard);
    }

    private void validatePath(Path path, MunicipalSourceProperties.Osm osm) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("OSM input file not found");
        }
        String name = path.getFileName().toString().toLowerCase();
        if (!(name.endsWith(".geojson") || name.endsWith(".json"))) {
            throw new IllegalArgumentException("OSM import accepts .geojson/.json interchange only");
        }
        // Optional allowlist of parent directories for hosted-beta safety.
        if (osm.getAllowedInputDir() != null && !osm.getAllowedInputDir().isBlank()) {
            Path allowed = Path.of(osm.getAllowedInputDir()).toAbsolutePath().normalize();
            if (!path.startsWith(allowed)) {
                throw new IllegalArgumentException("OSM input path outside allowed directory");
            }
        }
    }

    private String resolveClipVersion() {
        String configured = properties.getOsm().getClipVersion();
        if (configured == null || configured.isBlank()) {
            return IzmirClip.CLIP_VERSION;
        }
        return configured.trim();
    }

    private OsmImportResult empty(
            MunicipalSyncRunStatus status, boolean dryRun, String file, String sha,
            String category, String summary) {
        return new OsmImportResult(status, dryRun, file, sha, resolveClipVersion(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, category, summary, null);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String category(Exception ex) {
        String name = ex.getClass().getSimpleName().toLowerCase();
        if (name.contains("json") || name.contains("argument")) {
            return "contract";
        }
        return "import";
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }

    private record ConflationOutcome(boolean candidate, boolean auto, boolean review, boolean rejected, boolean hard) {
        static ConflationOutcome none() {
            return new ConflationOutcome(false, false, false, false, false);
        }
    }
}