package com.parkio.parking.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort;
import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort.DiscoveredPair;
import com.parkio.parking.externalsource.registry.LinkCandidateEvidence;
import com.parkio.parking.externalsource.registry.LinkCandidateEvidenceFactory;
import com.parkio.parking.externalsource.registry.LinkCandidateGenerationBounds;
import com.parkio.parking.externalsource.registry.LinkCandidatePolicy;
import com.parkio.parking.externalsource.registry.LinkCandidateScore;
import com.parkio.parking.externalsource.registry.RegistrySourceFamilyPair;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LinkCandidateGenerationOrchestrator {
    public record Request(
            String sourceFamilyLeft,
            String sourceFamilyRight,
            Double maxDistanceMeters,
            Integer leftRecordLimit,
            Integer pairLimit,
            Integer sampleLimit,
            boolean dryRun,
            boolean persistCandidates,
            List<UUID> leftFacilityIds,
            List<String> leftExternalIds,
            String algorithmVersion,
            String operatorUserId,
            String correlationId) {}

    private final RegistryProperties properties;
    private final LinkCandidatePairDiscoveryPort discovery;
    private final LinkCandidateGenerationRunPort runs;
    private final LinkCandidateGenerationService generation;
    private final LinkCandidateEvidenceFactory evidenceFactory;
    private final ObjectMapper objectMapper;
    private final RegistryMetrics metrics;
    private final Clock clock;

    public LinkCandidateGenerationOrchestrator(
            RegistryProperties properties,
            LinkCandidatePairDiscoveryPort discovery,
            LinkCandidateGenerationRunPort runs,
            LinkCandidateGenerationService generation,
            ObjectMapper objectMapper,
            RegistryMetrics metrics,
            Clock clock) {
        this.properties = properties;
        this.discovery = discovery;
        this.runs = runs;
        this.generation = generation;
        this.evidenceFactory = new LinkCandidateEvidenceFactory(objectMapper);
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.clock = clock;
    }

    public LinkCandidateGenerationRunPort.RunRecord generate(Request request) {
        if (!properties.isCandidateGenerationEnabled()) {
            throw new IllegalStateException("Registry candidate generation is disabled");
        }
        if (request.persistCandidates() && request.dryRun()) {
            throw new IllegalArgumentException("persistCandidates=true requires dryRun=false");
        }
        if (request.algorithmVersion() != null
                && !LinkCandidatePolicy.ALGORITHM_VERSION.equals(request.algorithmVersion())) {
            throw new IllegalArgumentException("unsupported algorithmVersion");
        }
        RegistrySourceFamilyPair pair =
                RegistrySourceFamilyPair.resolve(request.sourceFamilyLeft(), request.sourceFamilyRight());
        RegistrySourceFamilyPair.Family leftFamily = pair.leftFamily(request.sourceFamilyLeft());
        LinkCandidateGenerationBounds bounds = LinkCandidateGenerationBounds.normalize(
                request.maxDistanceMeters(), request.leftRecordLimit(),
                request.pairLimit(), request.sampleLimit());
        Instant started = clock.instant();
        String scope = json(Map.of(
                "leftFacilityIds", safe(request.leftFacilityIds()),
                "leftExternalIds", safe(request.leftExternalIds())));
        UUID runId = runs.tryStart(new LinkCandidateGenerationRunPort.StartRequest(
                        pair.key(), LinkCandidatePolicy.ALGORITHM_VERSION,
                        request.dryRun(), request.persistCandidates(),
                        bounds.maxDistanceMeters(), bounds.leftRecordLimit(),
                        bounds.pairLimit(), bounds.sampleLimit(), scope,
                        request.operatorUserId(), request.correlationId(), started))
                .orElseThrow(() -> {
                    metrics.generationRun(pair.key(), "concurrent_conflict", request.dryRun());
                    return new ConcurrentGenerationException(pair.key());
                });

        MutableAggregates totals = new MutableAggregates();
        List<Map<String, Object>> samples = new ArrayList<>();
        try {
            var result = discovery.discover(
                    pair, leftFamily, bounds.maxDistanceMeters(), bounds.leftRecordLimit(),
                    bounds.pairLimit(), safe(request.leftFacilityIds()), safe(request.leftExternalIds()));
            totals.leftRecords = result.leftRecordsConsidered();
            for (DiscoveredPair discovered : result.pairs()) {
                totals.pairs++;
                try {
                    process(discovered, request, totals, samples, bounds.sampleLimit());
                } catch (RuntimeException processingFailure) {
                    totals.failures++;
                    totals.skip("processing_failure");
                    addFailureSample(discovered, samples, bounds.sampleLimit());
                }
            }
            String status = totals.failures == 0 ? "COMPLETED" : "PARTIAL";
            complete(runId, pair.key(), status, totals, samples, null, started, request.dryRun());
        } catch (RuntimeException discoveryFailure) {
            totals.failures++;
            totals.skip("discovery_failure");
            complete(runId, pair.key(), "FAILED", totals, samples, "discovery_failure", started, request.dryRun());
            throw discoveryFailure;
        }
        return runs.findById(runId).orElseThrow();
    }

    private void process(
            DiscoveredPair pair,
            Request request,
            MutableAggregates totals,
            List<Map<String, Object>> samples,
            int sampleLimit) {
        if (!pair.left().facilityActive() || !pair.right().facilityActive()
                || !pair.left().linkActive() || !pair.right().linkActive()
                || !"ACTIVE".equals(pair.left().lifecycleState())
                || !"ACTIVE".equals(pair.right().lifecycleState())
                || pair.left().rawRecordHash() == null || pair.right().rawRecordHash() == null) {
            totals.skip("inactive_unpublished");
            addSample(pair, null, "inactive_unpublished", "skipped", samples, sampleLimit);
            return;
        }
        if (pair.left().facilityId().equals(pair.right().facilityId())) {
            totals.skip("same_canonical_facility");
            addSample(pair, null, "same_canonical_facility", "skipped", samples, sampleLimit);
            return;
        }
        if (discovery.alreadyLinked(pair)) {
            totals.skip("already_linked");
            addSample(pair, null, "already_linked", "skipped", samples, sampleLimit);
            return;
        }
        LinkCandidateEvidence evidence = evidenceFactory.create(pair);
        LinkCandidateScore score = LinkCandidatePolicy.evaluate(evidence);
        if (!score.reviewRequired()) {
            totals.skip(score.reasonCategory());
            addSample(pair, score, score.reasonCategory(), "skipped", samples, sampleLimit);
            return;
        }
        if (score.candidate()) totals.eligible++;
        if (!score.hardConflicts().isEmpty()) totals.hardConflicts++;
        String disposition = request.persistCandidates() && !request.dryRun() ? "pending" : "evaluated";
        if (request.persistCandidates() && !request.dryRun()) {
            LinkCandidateGenerationService.GenerationResult generated =
                    generation.generate(evidence, pair.left().facilityId(), pair.right().facilityId());
            if (generated.inserted()) totals.persisted++;
            else totals.duplicates++;
            disposition = generated.inserted() ? "persisted" : "duplicate_suppressed";
        }
        addSample(pair, score, score.reasonCategory(), disposition, samples, sampleLimit);
    }

    private void complete(
            UUID runId,
            String pair,
            String status,
            MutableAggregates totals,
            List<Map<String, Object>> samples,
            String failureCategory,
            Instant started,
            boolean dryRun) {
        Instant completed = clock.instant();
        long duration = Math.max(0, Duration.between(started, completed).toMillis());
        runs.complete(runId, status, totals.snapshot(), json(samples), failureCategory, completed, duration);
        metrics.generationRun(pair, status.toLowerCase(), dryRun);
        metrics.generationDuration(pair, dryRun, duration);
        metrics.generationPairs(pair, LinkCandidatePolicy.ALGORITHM_VERSION, dryRun, totals.pairs);
        generationCount(pair, "left_records_considered", "", dryRun, totals.leftRecords);
        generationCount(pair, "pairs_considered", "", dryRun, totals.pairs);
        generationCount(pair, "candidates_eligible", "", dryRun, totals.eligible);
        generationCount(pair, "candidates_persisted", "", dryRun, totals.persisted);
        generationCount(pair, "hard_conflicts", "hard_conflict", dryRun, totals.hardConflicts);
        generationCount(pair, "duplicates_suppressed", "duplicate", dryRun, totals.duplicates);
        generationCount(pair, "failures", failureCategory == null ? "processing_failure" : failureCategory,
                dryRun, totals.failures);
        totals.skips.forEach((reason, count) -> generationCount(pair, "skipped", reason, dryRun, count));
    }

    private void generationCount(String pair, String outcome, String reason, boolean dryRun, int count) {
        metrics.generationCount(
                pair, outcome, reason, LinkCandidatePolicy.ALGORITHM_VERSION, dryRun, count);
    }

    private void addFailureSample(DiscoveredPair pair, List<Map<String, Object>> samples, int limit) {
        addSample(pair, null, "processing_failure", "failed", samples, limit);
    }

    private void addSample(
            DiscoveredPair pair,
            LinkCandidateScore score,
            String reason,
            String disposition,
            List<Map<String, Object>> samples,
            int limit) {
        if (samples.size() >= limit) return;
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("leftSourceKey", pair.left().sourceKey());
        sample.put("leftExternalId", truncate(pair.left().externalId()));
        sample.put("rightSourceKey", pair.right().sourceKey());
        sample.put("rightExternalId", truncate(pair.right().externalId()));
        sample.put("distanceMeters", round(pair.distanceMeters(), 1));
        sample.put("reasonCategory", reason);
        sample.put("disposition", disposition);
        sample.put("supportingSignals", score == null ? List.of() : score.supportingSignals());
        sample.put("hardConflicts", score == null ? List.of() : score.hardConflicts());
        sample.put("totalScore", score == null ? null : round(score.total(), 3));
        samples.add(sample);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("cannot serialize generation audit", ex);
        }
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= 32) return value;
        return value.substring(0, 32);
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    private static <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    private static final class MutableAggregates {
        int leftRecords;
        int pairs;
        int eligible;
        int persisted;
        int hardConflicts;
        int duplicates;
        int failures;
        final Map<String, Integer> skips = new LinkedHashMap<>();

        void skip(String reason) {
            skips.merge(reason == null ? "unknown" : reason, 1, Integer::sum);
        }

        LinkCandidateGenerationRunPort.Aggregates snapshot() {
            return new LinkCandidateGenerationRunPort.Aggregates(
                    leftRecords, pairs, eligible, persisted, hardConflicts,
                    Map.copyOf(skips), duplicates, failures);
        }
    }
}
