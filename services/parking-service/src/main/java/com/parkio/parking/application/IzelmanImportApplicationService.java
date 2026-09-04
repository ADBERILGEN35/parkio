package com.parkio.parking.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalSourceLinkRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.application.port.OsmImportSupportRepository;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.izelman.IzelmanCsvReader;
import com.parkio.parking.externalsource.izelman.IzelmanFacilityMapper;
import com.parkio.parking.externalsource.izelman.IzelmanRoadsideMapper;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.externalsource.izelman.IzelmanTariffMapper;
import com.parkio.parking.externalsource.izelman.SourceAgeClassification;
import com.parkio.parking.externalsource.izelman.SourceAgeClassifier;
import com.parkio.parking.infrastructure.config.IzelmanProperties;
import com.parkio.parking.infrastructure.persistence.IzelmanImportRepositoryAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IzelmanImportApplicationService {
    private final IzelmanProperties properties;
    private final MunicipalDataSourceRepository sources;
    private final MunicipalFacilityRepository facilities;
    private final MunicipalSourceLinkRepository links;
    private final MunicipalSourceSyncRunRepository runs;
    private final OsmImportSupportRepository support;
    private final IzelmanImportRepositoryAdapter repository;
    private final IzelmanCsvReader reader;
    private final IzelmanFacilityMapper facilityMapper;
    private final IzelmanRoadsideMapper roadsideMapper;
    private final IzelmanTariffMapper tariffMapper;
    private final ObjectMapper json;
    private final JdbcClient jdbc;
    private final Clock clock;

    public IzelmanImportApplicationService(
            IzelmanProperties properties, MunicipalDataSourceRepository sources,
            MunicipalFacilityRepository facilities, MunicipalSourceLinkRepository links,
            MunicipalSourceSyncRunRepository runs, OsmImportSupportRepository support,
            IzelmanImportRepositoryAdapter repository, IzelmanCsvReader reader,
            IzelmanFacilityMapper facilityMapper, IzelmanRoadsideMapper roadsideMapper,
            IzelmanTariffMapper tariffMapper, ObjectMapper json, JdbcClient jdbc, Clock clock) {
        this.properties = properties;
        this.sources = sources;
        this.facilities = facilities;
        this.links = links;
        this.runs = runs;
        this.support = support;
        this.repository = repository;
        this.reader = reader;
        this.facilityMapper = facilityMapper;
        this.roadsideMapper = roadsideMapper;
        this.tariffMapper = tariffMapper;
        this.json = json;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public IzelmanImportResult importConfigured(String sourceKey, boolean dryRun) {
        if (properties.getAllowedInputDir() == null || properties.getAllowedInputDir().isBlank()) {
            throw new IllegalArgumentException("parkio.municipal.izelman.allowed-input-dir is required");
        }
        return importPath(sourceKey,
                Path.of(properties.getAllowedInputDir()).resolve(fileName(sourceKey)), dryRun);
    }

    @Transactional
    public IzelmanImportResult importPath(String sourceKey, Path input, boolean dryRun) {
        validateEnabled(sourceKey);
        Path path = input.toAbsolutePath().normalize();
        validatePath(path);
        Instant now = clock.instant();
        var source = sources.requireBySourceKey(sourceKey);
        Optional<UUID> runId = runs.tryStart(source.id(), UUID.randomUUID().toString(), now);
        String type = dataType(sourceKey);
        if (runId.isEmpty()) return result(MunicipalSyncRunStatus.SKIPPED, sourceKey, type, dryRun,
                0, 0, 0, 0, 0, 0, 0, SourceAgeClassification.UNKNOWN, "concurrent_run");
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > properties.getMaxInputBytes()) throw new IllegalArgumentException("input exceeds max size");
            var csv = reader.read(bytes);
            Instant contentAt = IzelmanSourceKeys.CONTENT_DATES.get(sourceKey);
            SourceAgeClassification age = SourceAgeClassifier.classify(contentAt, now,
                    properties.getAgingAfterDays(), properties.getHistoricalAfterDays());
            int accepted = 0, rejected = 0, inserted = 0, updated = 0, unchanged = 0;
            int plansAccepted = 0, bandsAccepted = 0, assignmentsAccepted = 0;
            int trueDuplicates = 0, textualFallbacks = 0;
            Set<String> seen = new HashSet<>();
            Map<String, String> seenTariffHashes = new LinkedHashMap<>();
            Map<String, Integer> rejectReasons = new LinkedHashMap<>();
            for (Map<String, String> row : csv.rows()) {
                try {
                    IzelmanImportRepositoryAdapter.UpsertOutcome outcome;
                    String externalId;
                    if ("FACILITY".equals(type)) {
                        var mapped = facilityMapper.map(sourceKey, row);
                        externalId = mapped.externalId();
                        if (!seen.add(externalId)) { rejected++; rejectReasons.merge("duplicate", 1, Integer::sum); continue; }
                        accepted++;
                        if (dryRun) continue;
                        var upsert = facilities.upsert(source.id(), mapped, now);
                        links.upsert(upsert.id(), source.id(), mapped, now);
                        jdbc.sql("UPDATE municipal_parking_facilities SET primary_source_key=:key WHERE id=:id")
                                .param("key", sourceKey).param("id", upsert.id()).update();
                        outcome = upsert.inserted() ? IzelmanImportRepositoryAdapter.UpsertOutcome.INSERTED
                                : upsert.changed() ? IzelmanImportRepositoryAdapter.UpsertOutcome.UPDATED
                                : IzelmanImportRepositoryAdapter.UpsertOutcome.UNCHANGED;
                    } else if ("ROADSIDE".equals(type)) {
                        var mapped = roadsideMapper.map(row);
                        externalId = mapped.externalId();
                        if (!seen.add(externalId)) { rejected++; rejectReasons.merge("duplicate", 1, Integer::sum); continue; }
                        accepted++;
                        if (dryRun) continue;
                        outcome = repository.upsertRoadside(source.id(), mapped, contentAt, age, now);
                    } else {
                        var mapped = tariffMapper.map(row, contentAt, now);
                        externalId = mapped.externalId();
                        textualFallbacks += tariffMapper.countTextualFallbackCells(row);
                        String priorHash = seenTariffHashes.get(externalId);
                        if (priorHash != null) {
                            if (priorHash.equals(mapped.rawRecordHash())) {
                                trueDuplicates++;
                                rejected++;
                                rejectReasons.merge("true_duplicate_source_row", 1, Integer::sum);
                            } else {
                                rejected++;
                                rejectReasons.merge("conflicting_plan_identity", 1, Integer::sum);
                            }
                            continue;
                        }
                        seenTariffHashes.put(externalId, mapped.rawRecordHash());
                        seen.add(externalId);
                        if (mapped.bands().isEmpty()) {
                            rejected++;
                            rejectReasons.merge("invalid_row", 1, Integer::sum);
                            continue;
                        }
                        accepted++;
                        plansAccepted++;
                        bandsAccepted += mapped.bands().size();
                        if (dryRun) continue;
                        outcome = repository.upsertTariff(source.id(), mapped, contentAt, age, now);
                    }
                    if (outcome == IzelmanImportRepositoryAdapter.UpsertOutcome.INSERTED) inserted++;
                    else if (outcome == IzelmanImportRepositoryAdapter.UpsertOutcome.UPDATED) updated++;
                    else unchanged++;
                } catch (RuntimeException ex) {
                    rejected++;
                    rejectReasons.merge("invalid_record", 1, Integer::sum);
                }
            }
            int deactivated = 0;
            if (!dryRun && source.completeSnapshot() && accepted > 0) {
                if (!runs.isRunning(runId.get())) {
                    return result(MunicipalSyncRunStatus.FAILED, sourceKey, type, dryRun,
                            csv.rows().size(), accepted, rejected, inserted, updated, unchanged, 0,
                            age, "ownership_lost");
                }
                if ("FACILITY".equals(type)) deactivated = support.deactivateMissing(source.id(), seen, now);
                else if ("ROADSIDE".equals(type)) deactivated = repository.deactivateMissingRoadside(source.id(), seen, now);
            }
            Map<String, Object> reportMap = new LinkedHashMap<>();
            reportMap.put("rejectReasons", rejectReasons);
            reportMap.put("completeSnapshot", source.completeSnapshot());
            reportMap.put("occupancyWritten", false);
            reportMap.put("autoMatch", false);
            reportMap.put("plansAccepted", plansAccepted);
            reportMap.put("bandsAccepted", bandsAccepted);
            reportMap.put("assignmentsAccepted", assignmentsAccepted);
            reportMap.put("trueDuplicates", trueDuplicates);
            reportMap.put("textualFallbacks", textualFallbacks);
            String report = json.writeValueAsString(reportMap);
            String sha = IzelmanCsvReader.sha256(bytes);
            MunicipalSyncResult sync = new MunicipalSyncResult(MunicipalSyncRunStatus.SUCCESS,
                    csv.rows().size(), accepted, rejected, inserted, updated, unchanged, 0, null, null);
            if (!runs.complete(runId.get(), clock.instant(), sync, null, sha)) {
                return result(MunicipalSyncRunStatus.FAILED, sourceKey, type, dryRun,
                        csv.rows().size(), accepted, rejected, inserted, updated, unchanged, deactivated,
                        age, "ownership_lost");
            }
            if (!dryRun) sources.markSuccessful(source.id(), clock.instant());
            repository.saveRun(runId.get(), source.id(), type, path.getFileName().toString(), sha,
                    csv.encoding(), csv.delimiter(), csv.schemaFingerprint(), contentAt, age, dryRun, report, clock.instant());
            return new IzelmanImportResult(MunicipalSyncRunStatus.SUCCESS, sourceKey, type, dryRun, csv.rows().size(),
                    accepted, rejected, inserted, updated, unchanged, deactivated, age, null,
                    plansAccepted, bandsAccepted, assignmentsAccepted, trueDuplicates, textualFallbacks);
        } catch (Exception ex) {
            MunicipalSyncResult failed = new MunicipalSyncResult(MunicipalSyncRunStatus.FAILED,
                    0, 0, 0, 0, 0, 0, 0, "import", truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            runs.complete(runId.get(), clock.instant(), failed, null, null);
            return result(MunicipalSyncRunStatus.FAILED, sourceKey, type, dryRun,
                    0, 0, 0, 0, 0, 0, 0, SourceAgeClassification.UNKNOWN,
                    truncate(ex.getClass().getSimpleName()));
        }
    }

    private void validateEnabled(String key) {
        if (!properties.isEnabled()) throw new IllegalStateException("İZELMAN import is disabled");
        if (!IzelmanSourceKeys.ALL.contains(key)) throw new IllegalArgumentException("unsupported İZELMAN source key");
        boolean enabled = IzelmanSourceKeys.ROADSIDE.equals(key) ? properties.isRoadsideImportEnabled()
                : IzelmanSourceKeys.TARIFFS.equals(key) ? properties.isTariffImportEnabled()
                : properties.isFacilityImportEnabled();
        if (!enabled) throw new IllegalStateException("İZELMAN data-type import is disabled");
        if (properties.isAutoMatchEnabled()) throw new IllegalStateException("İZELMAN auto-match must remain disabled");
    }

    private void validatePath(Path path) {
        if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase().endsWith(".csv"))
            throw new IllegalArgumentException("İZELMAN CSV input file not found");
        Path allowed = Path.of(properties.getAllowedInputDir()).toAbsolutePath().normalize();
        if (!path.startsWith(allowed)) throw new IllegalArgumentException("İZELMAN input path outside allowed directory");
    }

    private static String dataType(String key) {
        return IzelmanSourceKeys.ROADSIDE.equals(key) ? "ROADSIDE"
                : IzelmanSourceKeys.TARIFFS.equals(key) ? "TARIFF" : "FACILITY";
    }
    private static String fileName(String key) { return key + ".csv"; }
    private static String truncate(String value) { return value == null || value.length() <= 1024 ? value : value.substring(0, 1024); }
    private static IzelmanImportResult result(MunicipalSyncRunStatus status, String key, String type, boolean dry,
            int read, int accepted, int rejected, int inserted, int updated, int unchanged, int deactivated,
            SourceAgeClassification age, String error) {
        return new IzelmanImportResult(status, key, type, dry, read, accepted, rejected,
                inserted, updated, unchanged, deactivated, age, error);
    }
}
