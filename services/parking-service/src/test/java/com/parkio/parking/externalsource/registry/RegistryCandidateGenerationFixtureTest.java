package com.parkio.parking.externalsource.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.LinkCandidateGenerationOrchestrator;
import com.parkio.parking.application.LinkCandidateGenerationService;
import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistryCandidateGenerationFixtureTest {
    @Test
    void fixtureCoversDryRunAndPersistIdempotencyAggregates() {
        RegistryProperties properties = new RegistryProperties();
        properties.setCandidateGenerationEnabled(true);
        DiscoveredFixture discovery = new DiscoveredFixture();
        InMemoryRuns runs = new InMemoryRuns();
        LinkCandidateGenerationService generation = mock(LinkCandidateGenerationService.class);
        when(generation.generate(any(), any(), any()))
                .thenReturn(new LinkCandidateGenerationService.GenerationResult(
                        true, true, UUID.randomUUID(), "multi_signal_candidate", "PENDING"))
                .thenReturn(new LinkCandidateGenerationService.GenerationResult(
                        true, false, null, "multi_signal_candidate", "PENDING"));
        LinkCandidateGenerationOrchestrator orchestrator = new LinkCandidateGenerationOrchestrator(
                properties,
                discovery,
                runs,
                generation,
                new ObjectMapper(),
                mock(RegistryMetrics.class),
                Clock.fixed(Instant.parse("2026-07-30T19:00:00Z"), ZoneOffset.UTC));

        var dryRun = orchestrator.generate(request(true, false));
        var firstPersist = orchestrator.generate(request(false, true));
        var repeatPersist = orchestrator.generate(request(false, true));

        assertThat(dryRun.aggregates().candidatesEligible()).isEqualTo(1);
        assertThat(dryRun.aggregates().candidatesPersisted()).isZero();
        assertThat(firstPersist.aggregates().candidatesPersisted()).isEqualTo(1);
        assertThat(firstPersist.aggregates().duplicatesSuppressed()).isZero();
        assertThat(repeatPersist.aggregates().candidatesPersisted()).isZero();
        assertThat(repeatPersist.aggregates().duplicatesSuppressed()).isEqualTo(1);
    }

    private static LinkCandidateGenerationOrchestrator.Request request(
            boolean dryRun, boolean persistCandidates) {
        return new LinkCandidateGenerationOrchestrator.Request(
                "IZUM", "OSM", null, null, null, null,
                dryRun, persistCandidates, List.of(), List.of(), null, "fixture", "fixture");
    }

    private static final class DiscoveredFixture implements LinkCandidatePairDiscoveryPort {
        private final DiscoveredPair pair = new DiscoveredPair(
                source("izmir-izum-otoparklar", "izum-fixture"),
                source("osm-geofabrik-turkey", "osm-fixture"),
                8.0);

        @Override
        public DiscoveryResult discover(
                RegistrySourceFamilyPair sourcePair,
                RegistrySourceFamilyPair.Family leftFamily,
                double maxDistanceMeters,
                int leftRecordLimit,
                int pairLimit,
                List<UUID> leftFacilityIds,
                List<String> leftExternalIds) {
            return new DiscoveryResult(List.of(pair), 1);
        }

        @Override
        public boolean alreadyLinked(DiscoveredPair discoveredPair) {
            return false;
        }

        private static SourceRecord source(String sourceKey, String externalId) {
            return new SourceRecord(
                    UUID.randomUUID(), UUID.randomUUID(), sourceKey, externalId, "fixture-v1",
                    "Konak Otoparki", "Izmir Belediyesi", MunicipalFacilityType.OFF_STREET,
                    MunicipalAccessClassification.PUBLIC, 100, 38.42, 27.14,
                    "Ataturk 1", "{\"district\":\"Konak\"}", true, true, "ACTIVE");
        }
    }

    private static final class InMemoryRuns implements LinkCandidateGenerationRunPort {
        private StartRequest started;
        private RunRecord completed;

        @Override
        public Optional<UUID> tryStart(StartRequest request) {
            started = request;
            completed = null;
            return Optional.of(UUID.randomUUID());
        }

        @Override
        public void complete(
                UUID runId,
                String status,
                Aggregates aggregates,
                String samplesJson,
                String failureCategory,
                Instant completedAt,
                long durationMs) {
            completed = new RunRecord(
                    runId, started.sourceFamilyPair(), started.algorithmVersion(), started.dryRun(),
                    started.persistCandidates(), started.maxDistanceMeters(), started.leftRecordLimit(),
                    started.pairLimit(), started.sampleLimit(), started.leftScopeJson(), status,
                    aggregates, samplesJson, failureCategory, started.operatorUserId(),
                    started.correlationId(), started.startedAt(), completedAt, durationMs);
        }

        @Override
        public Optional<RunRecord> findById(UUID id) {
            return Optional.ofNullable(completed);
        }

        @Override
        public RunPage findPage(int page, int size, String sourceFamilyPair) {
            return new RunPage(completed == null ? List.of() : List.of(completed), page, size,
                    completed == null ? 0 : 1);
        }

        @Override
        public int countActiveRunning() {
            return completed == null && started != null ? 1 : 0;
        }

        @Override
        public Optional<RunRecord> findLatestCompleted() {
            return Optional.ofNullable(completed);
        }

        @Override
        public int countStaleRunning(Instant olderThan) {
            return 0;
        }
    }
}
