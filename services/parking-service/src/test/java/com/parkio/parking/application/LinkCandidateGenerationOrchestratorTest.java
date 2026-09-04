package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort;
import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort.DiscoveredPair;
import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort.SourceRecord;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LinkCandidateGenerationOrchestratorTest {
    @Mock LinkCandidatePairDiscoveryPort discovery;
    @Mock LinkCandidateGenerationRunPort runs;
    @Mock LinkCandidateGenerationService generation;
    @Mock RegistryMetrics metrics;

    private LinkCandidateGenerationOrchestrator orchestrator;
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RegistryProperties properties = new RegistryProperties();
        properties.setCandidateGenerationEnabled(true);
        orchestrator = new LinkCandidateGenerationOrchestrator(
                properties, discovery, runs, generation, new ObjectMapper(), metrics,
                Clock.fixed(Instant.parse("2026-07-30T19:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void dryRunEvaluatesWithoutCandidateWritesAndCompletesAudit() {
        DiscoveredPair pair = pair();
        arrangeDiscovery(List.of(pair));
        when(discovery.alreadyLinked(pair)).thenReturn(false);
        when(runs.findById(runId)).thenReturn(Optional.of(completedRun(true, "COMPLETED")));

        var result = orchestrator.generate(request(true, false));

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(generation, never()).generate(any(), any(), any());
        ArgumentCaptor<LinkCandidateGenerationRunPort.Aggregates> aggregates =
                ArgumentCaptor.forClass(LinkCandidateGenerationRunPort.Aggregates.class);
        verify(runs).complete(
                org.mockito.ArgumentMatchers.eq(runId),
                org.mockito.ArgumentMatchers.eq("COMPLETED"),
                aggregates.capture(), any(), org.mockito.ArgumentMatchers.isNull(), any(), any(Long.class));
        assertThat(aggregates.getValue().pairsConsidered()).isEqualTo(1);
        assertThat(aggregates.getValue().candidatesPersisted()).isZero();
    }

    @Test
    void alreadyLinkedSkipsWithoutCallingGeneration() {
        DiscoveredPair pair = pair();
        arrangeDiscovery(List.of(pair));
        when(discovery.alreadyLinked(pair)).thenReturn(true);
        when(runs.findById(runId)).thenReturn(Optional.of(completedRun(false, "COMPLETED")));

        orchestrator.generate(request(false, true));

        verify(generation, never()).generate(any(), any(), any());
        ArgumentCaptor<LinkCandidateGenerationRunPort.Aggregates> aggregates =
                ArgumentCaptor.forClass(LinkCandidateGenerationRunPort.Aggregates.class);
        verify(runs).complete(
                org.mockito.ArgumentMatchers.eq(runId),
                org.mockito.ArgumentMatchers.eq("COMPLETED"),
                aggregates.capture(), any(), org.mockito.ArgumentMatchers.isNull(), any(), any(Long.class));
        assertThat(aggregates.getValue().skips()).containsEntry("already_linked", 1);
    }

    @Test
    void processingFailureYieldsPartialStatusWhenOnePairThrows() {
        DiscoveredPair failing = pair();
        arrangeDiscovery(List.of(failing));
        when(discovery.alreadyLinked(failing)).thenReturn(false);
        when(generation.generate(any(), any(), any())).thenThrow(new RuntimeException("write failed"));
        when(runs.findById(runId)).thenReturn(Optional.of(completedRun(false, "PARTIAL")));

        var result = orchestrator.generate(request(false, true));

        assertThat(result.status()).isEqualTo("PARTIAL");
        ArgumentCaptor<LinkCandidateGenerationRunPort.Aggregates> aggregates =
                ArgumentCaptor.forClass(LinkCandidateGenerationRunPort.Aggregates.class);
        verify(runs).complete(
                org.mockito.ArgumentMatchers.eq(runId),
                org.mockito.ArgumentMatchers.eq("PARTIAL"),
                aggregates.capture(), any(), org.mockito.ArgumentMatchers.isNull(), any(), any(Long.class));
        assertThat(aggregates.getValue().failures()).isEqualTo(1);
        assertThat(aggregates.getValue().skips()).containsEntry("processing_failure", 1);
    }

    @Test
    void rejectsInvalidPersistDryRunCombinationBeforeLock() {
        assertThatThrownBy(() -> orchestrator.generate(request(true, true)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(runs, never()).tryStart(any());
    }

    @Test
    void reportsConcurrentRunConflict() {
        when(runs.tryStart(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orchestrator.generate(request(false, false)))
                .isInstanceOf(ConcurrentGenerationException.class);
    }

    private void arrangeDiscovery(List<DiscoveredPair> pairs) {
        when(runs.tryStart(any())).thenReturn(Optional.of(runId));
        when(discovery.discover(any(), any(), any(Double.class), any(Integer.class), any(Integer.class), any(), any()))
                .thenReturn(new LinkCandidatePairDiscoveryPort.DiscoveryResult(pairs, 1));
    }

    private LinkCandidateGenerationOrchestrator.Request request(boolean dryRun, boolean persist) {
        return new LinkCandidateGenerationOrchestrator.Request(
                "IZUM", "OSM", null, null, null, null, dryRun, persist,
                List.of(), List.of(), null, "admin", "test");
    }

    private DiscoveredPair pair() {
        return new DiscoveredPair(source("izmir-izum-otoparklar", "izum-1"),
                source("osm-geofabrik-turkey", "osm-1"), 8);
    }

    private SourceRecord source(String key, String external) {
        return new SourceRecord(
                UUID.randomUUID(), UUID.randomUUID(), key, external, "v1",
                "Konak Otoparki", "Izmir Belediyesi", MunicipalFacilityType.OFF_STREET,
                MunicipalAccessClassification.PUBLIC, 100, 38.42, 27.14,
                "Ataturk 1", "{\"district\":\"Konak\"}", true, true, "ACTIVE");
    }

    private LinkCandidateGenerationRunPort.RunRecord completedRun(boolean dryRun, String status) {
        return new LinkCandidateGenerationRunPort.RunRecord(
                runId, "IZUM_OSM", "registry-link-candidate-v1", dryRun, !dryRun,
                100, 100, 1000, 20, "{}", status,
                new LinkCandidateGenerationRunPort.Aggregates(1, 1, 1, 0, 0, Map.of(), 0,
                        "PARTIAL".equals(status) ? 1 : 0),
                "[]", null, "admin", "test", Instant.now(), Instant.now(), 1L);
    }
}
