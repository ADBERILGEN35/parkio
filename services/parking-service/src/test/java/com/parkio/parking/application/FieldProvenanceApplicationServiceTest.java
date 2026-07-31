package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.RegistryPersistencePort;
import com.parkio.parking.externalsource.registry.FieldProvenanceSelection;
import com.parkio.parking.externalsource.registry.IngestFieldProvenancePolicy;
import com.parkio.parking.externalsource.registry.RegistryField;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FieldProvenanceApplicationServiceTest {
    @Mock RegistryPersistencePort persistence;
    @Mock RegistryMetrics metrics;
    RegistryProperties properties;
    FieldProvenanceApplicationService service;

    @BeforeEach
    void setUp() {
        properties = new RegistryProperties();
        properties.setProvenanceIngestWriteEnabled(true);
        properties.setProvenancePublicationEnabled(false);
        service = new FieldProvenanceApplicationService(persistence, metrics, properties);
    }

    @Test
    void insertsWhenMissing() {
        UUID facilityId = UUID.randomUUID();
        when(persistence.findProvenance(facilityId, "NAME")).thenReturn(Optional.empty());

        service.applyIngestSelections(
                facilityId,
                "izmir-izum-otoparklar",
                "ufid-1",
                Instant.parse("2026-07-31T12:00:00Z"),
                IngestFieldProvenancePolicy.REASON_IZUM_SYNC,
                List.of(new IngestFieldProvenancePolicy.SuppliedField(RegistryField.NAME)));

        ArgumentCaptor<FieldProvenanceSelection> captor = ArgumentCaptor.forClass(FieldProvenanceSelection.class);
        verify(persistence).upsertProvenance(captor.capture());
        assertThat(captor.getValue().sourceKey()).isEqualTo("izmir-izum-otoparklar");
        assertThat(captor.getValue().field()).isEqualTo(RegistryField.NAME);
        verify(metrics).provenance(RegistryField.NAME, "selected");
    }

    @Test
    void skipsOtherSourceWithoutOverwrite() {
        UUID facilityId = UUID.randomUUID();
        when(persistence.findProvenance(facilityId, "NAME")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(
                        facilityId, "NAME", "osm-geofabrik-turkey", "way/1", 1)));

        service.applyIngestSelections(
                facilityId,
                "izmir-izum-otoparklar",
                "ufid-1",
                Instant.parse("2026-07-31T12:00:00Z"),
                IngestFieldProvenancePolicy.REASON_IZUM_SYNC,
                List.of(new IngestFieldProvenancePolicy.SuppliedField(RegistryField.NAME)));

        verify(persistence, never()).upsertProvenance(any());
        verify(metrics).provenance(RegistryField.NAME, "skipped_other_source");
    }

    @Test
    void unchangedWhenSameSourceAndRecord() {
        UUID facilityId = UUID.randomUUID();
        when(persistence.findProvenance(facilityId, "COORDINATES")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(
                        facilityId, "COORDINATES", "osm-geofabrik-turkey", "way/1", 2)));

        service.applyIngestSelections(
                facilityId,
                "osm-geofabrik-turkey",
                "way/1",
                Instant.parse("2026-07-31T12:00:00Z"),
                IngestFieldProvenancePolicy.REASON_OSM_IMPORT,
                List.of(new IngestFieldProvenancePolicy.SuppliedField(RegistryField.COORDINATES)));

        verify(persistence, never()).upsertProvenance(any());
        verify(metrics).provenance(RegistryField.COORDINATES, "unchanged");
    }

    @Test
    void updatesWhenSameSourceRecordIdChanges() {
        UUID facilityId = UUID.randomUUID();
        when(persistence.findProvenance(facilityId, "OPERATOR")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(
                        facilityId, "OPERATOR", "izmir-izum-otoparklar", "old-id", 1)));

        service.applyIngestSelections(
                facilityId,
                "izmir-izum-otoparklar",
                "new-id",
                Instant.parse("2026-07-31T12:00:00Z"),
                IngestFieldProvenancePolicy.REASON_IZUM_SYNC,
                List.of(new IngestFieldProvenancePolicy.SuppliedField(RegistryField.OPERATOR)));

        verify(persistence).upsertProvenance(any());
        verify(metrics).provenance(RegistryField.OPERATOR, "updated");
    }

    @Test
    void killSwitchSkipsWrites() {
        properties.setProvenanceIngestWriteEnabled(false);
        UUID facilityId = UUID.randomUUID();

        service.applyIngestSelections(
                facilityId,
                "izmir-izum-otoparklar",
                "ufid-1",
                Instant.parse("2026-07-31T12:00:00Z"),
                IngestFieldProvenancePolicy.REASON_IZUM_SYNC,
                List.of(new IngestFieldProvenancePolicy.SuppliedField(RegistryField.NAME)));

        verify(persistence, never()).findProvenance(any(), any());
        verify(persistence, never()).upsertProvenance(any());
        verify(metrics).provenance(eq(RegistryField.NAME), eq("skipped_disabled"));
    }

    @Test
    void legacySelectStillWritesSelected() {
        FieldProvenanceSelection selection = new FieldProvenanceSelection(
                UUID.randomUUID(),
                RegistryField.NAME,
                "izmir-izum-otoparklar",
                "ufid",
                null,
                Instant.parse("2026-07-31T12:00:00Z"),
                FieldProvenanceSelection.SourceAgeClass.CURRENT,
                "SELECTED",
                "manual",
                Instant.parse("2026-07-31T12:00:00Z"));
        service.select(selection);
        verify(persistence).upsertProvenance(selection);
        verify(metrics).provenance(RegistryField.NAME, "selected");
    }
}
