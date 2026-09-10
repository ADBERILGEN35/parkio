package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.RegistryPersistencePort;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.registry.FieldProvenanceSelection;
import com.parkio.parking.externalsource.registry.IngestFieldProvenancePolicy;
import com.parkio.parking.externalsource.registry.RegistryField;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import java.time.Instant;
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
class FieldProvenanceApplicationServiceTest {
    private static final Instant TS = Instant.parse("2026-07-31T12:00:00Z");
    private static final String OSM = "osm-geofabrik-turkey";
    private static final String IZUM = "izmir-izum-otoparklar";

    @Mock RegistryPersistencePort persistence;
    @Mock RegistryMetrics metrics;
    RegistryProperties properties;
    FieldProvenanceApplicationService service;
    UUID facilityId;

    @BeforeEach
    void setUp() {
        properties = new RegistryProperties();
        properties.setProvenanceIngestWriteEnabled(true);
        properties.setProvenancePublicationEnabled(false);
        service = new FieldProvenanceApplicationService(persistence, metrics, properties);
        facilityId = UUID.randomUUID();
        lenient().when(persistence.findProvenance(any(), anyString())).thenReturn(Optional.empty());
        lenient().when(persistence.deleteProvenanceIfSourceOwns(any(), anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    void insertsWhenMissing() {
        service.applyIngestSelections(
                facilityId,
                IZUM,
                "ufid-1",
                TS,
                IngestFieldProvenancePolicy.REASON_IZUM_SYNC,
                List.of(new IngestFieldProvenancePolicy.SuppliedField(RegistryField.NAME)));

        ArgumentCaptor<FieldProvenanceSelection> captor = ArgumentCaptor.forClass(FieldProvenanceSelection.class);
        verify(persistence).upsertProvenance(captor.capture());
        assertThat(captor.getValue().sourceKey()).isEqualTo(IZUM);
        assertThat(captor.getValue().field()).isEqualTo(RegistryField.NAME);
        verify(metrics).provenance(eq(RegistryField.NAME), eq("selected"), eq("izum"), anyString(), eq("select"));
        verify(persistence, never()).deleteProvenanceIfSourceOwns(any(), anyString(), anyString());
    }

    @Test
    void skipsOtherSourceWithoutOverwrite() {
        when(persistence.findProvenance(facilityId, "NAME")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(facilityId, "NAME", OSM, "way/1", 1)));

        service.applyIngestSelections(
                facilityId,
                IZUM,
                "ufid-1",
                TS,
                IngestFieldProvenancePolicy.REASON_IZUM_SYNC,
                List.of(new IngestFieldProvenancePolicy.SuppliedField(RegistryField.NAME)));

        verify(persistence, never()).upsertProvenance(any());
        verify(persistence, never()).deleteProvenanceIfSourceOwns(eq(facilityId), eq("NAME"), anyString());
        verify(metrics).provenance(eq(RegistryField.NAME), eq("skipped_other_source"), eq("izum"), anyString(), eq("select"));
    }

    @Test
    void unchangedWhenSameSourceAndRecord() {
        when(persistence.findProvenance(facilityId, "COORDINATES")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(facilityId, "COORDINATES", OSM, "way/1", 2)));

        service.applyIngestSelections(
                facilityId,
                OSM,
                "way/1",
                TS,
                IngestFieldProvenancePolicy.REASON_OSM_IMPORT,
                List.of(new IngestFieldProvenancePolicy.SuppliedField(RegistryField.COORDINATES)));

        verify(persistence, never()).upsertProvenance(any());
        verify(metrics).provenance(
                eq(RegistryField.COORDINATES), eq("unchanged"), eq("osm"), anyString(), eq("select"));
    }

    @Test
    void updatesWhenSameSourceRecordIdChanges() {
        when(persistence.findProvenance(facilityId, "OPERATOR")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(facilityId, "OPERATOR", IZUM, "old-id", 1)));

        service.applyIngestSelections(
                facilityId,
                IZUM,
                "new-id",
                TS,
                IngestFieldProvenancePolicy.REASON_IZUM_SYNC,
                List.of(new IngestFieldProvenancePolicy.SuppliedField(RegistryField.OPERATOR)));

        verify(persistence).upsertProvenance(any());
        verify(metrics).provenance(eq(RegistryField.OPERATOR), eq("updated"), eq("izum"), anyString(), eq("select"));
    }

    @Test
    void killSwitchSkipsWritesAndWithdrawal() {
        properties.setProvenanceIngestWriteEnabled(false);

        service.applyIngestSelections(
                facilityId,
                OSM,
                "way/1",
                TS,
                IngestFieldProvenancePolicy.REASON_OSM_IMPORT,
                List.of());

        verify(persistence, never()).findProvenance(any(), any());
        verify(persistence, never()).upsertProvenance(any());
        verify(persistence, never()).deleteProvenanceIfSourceOwns(any(), anyString(), anyString());
    }

    @Test
    void killSwitchSkipsSelectionMetrics() {
        properties.setProvenanceIngestWriteEnabled(false);

        service.applyIngestSelections(
                facilityId,
                IZUM,
                "ufid-1",
                TS,
                IngestFieldProvenancePolicy.REASON_IZUM_SYNC,
                List.of(new IngestFieldProvenancePolicy.SuppliedField(RegistryField.NAME)));

        verify(metrics).provenance(eq(RegistryField.NAME), eq("skipped_disabled"), eq("izum"), anyString(), eq("select"));
        verify(persistence, never()).deleteProvenanceIfSourceOwns(any(), anyString(), anyString());
    }

    @Test
    void legacySelectStillWritesSelected() {
        FieldProvenanceSelection selection = new FieldProvenanceSelection(
                UUID.randomUUID(),
                RegistryField.NAME,
                IZUM,
                "ufid",
                null,
                TS,
                FieldProvenanceSelection.SourceAgeClass.CURRENT,
                "SELECTED",
                "manual",
                TS);
        service.select(selection);
        verify(persistence).upsertProvenance(selection);
        verify(metrics).provenance(eq(RegistryField.NAME), eq("selected"), eq("izum"), anyString(), eq("select"));
    }

    @Test
    void realNameCreatesNameProvenance() {
        NormalizedMunicipalFacility facility = osmFacility("way/1", "Konak Otopark", "Op");
        service.applyOsmIngest(facilityId, facility, true, TS);
        ArgumentCaptor<FieldProvenanceSelection> captor = ArgumentCaptor.forClass(FieldProvenanceSelection.class);
        verify(persistence, times(4)).upsertProvenance(captor.capture());
        assertThat(captor.getAllValues()).extracting(FieldProvenanceSelection::field)
                .contains(RegistryField.NAME, RegistryField.COORDINATES, RegistryField.OPERATOR, RegistryField.ATTRIBUTION);
        verify(metrics).provenance(eq(RegistryField.NAME), eq("selected"), eq("osm"), anyString(), eq("select"));
    }

    @Test
    void realNameToDifferentRealNameUpdatesProvenance() {
        when(persistence.findProvenance(facilityId, "NAME")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(facilityId, "NAME", OSM, "way/old", 1)));

        service.applyOsmIngest(facilityId, osmFacility("way/1", "Yeni Ad", null), true, TS);

        ArgumentCaptor<FieldProvenanceSelection> captor = ArgumentCaptor.forClass(FieldProvenanceSelection.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce()).upsertProvenance(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(s -> s.field() == RegistryField.NAME);
        verify(metrics).provenance(eq(RegistryField.NAME), eq("updated"), eq("osm"), anyString(), eq("select"));
        verify(persistence, never()).deleteProvenanceIfSourceOwns(eq(facilityId), eq("NAME"), eq(OSM));
    }

    @Test
    void realNameToOperatorFallbackWithdrawsName() {
        stubOwned(RegistryField.NAME, OSM, "way/1");
        stubOwned(RegistryField.OPERATOR, OSM, "way/1");
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");

        NormalizedMunicipalFacility facility = osmFacility("way/1", "Belediye Otoparkı", "Belediye");
        service.applyOsmIngest(facilityId, facility, false, TS);

        verify(persistence).deleteProvenanceIfSourceOwns(facilityId, "NAME", OSM);
        verify(metrics).provenance(
                eq(RegistryField.NAME), eq("withdrawn_stale"), eq("osm"), anyString(), eq("reconcile"));
        verify(persistence, never()).deleteProvenanceIfSourceOwns(facilityId, "OPERATOR", OSM);
        verify(persistence, never()).deleteProvenanceIfSourceOwns(facilityId, "ATTRIBUTION", OSM);
    }

    @Test
    void realNameToBrandFallbackWithdrawsName() {
        stubOwned(RegistryField.NAME, OSM, "way/1");
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");

        // Brand-only fallback: no operator, no NAME claim
        NormalizedMunicipalFacility facility = new NormalizedMunicipalFacility(
                "way/1",
                null,
                null,
                "Parkio Otoparkı",
                null,
                38.4,
                27.1,
                null,
                MunicipalAccessClassification.PUBLIC,
                Map.of(),
                "hash");
        service.applyOsmIngest(facilityId, facility, false, TS);

        verify(persistence).deleteProvenanceIfSourceOwns(facilityId, "NAME", OSM);
        verify(metrics).provenance(
                eq(RegistryField.NAME), eq("withdrawn_stale"), eq("osm"), anyString(), eq("reconcile"));
    }

    @Test
    void realNameToTypeFallbackWithdrawsName() {
        stubOwned(RegistryField.NAME, OSM, "way/1");
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.FACILITY_TYPE, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");

        NormalizedMunicipalFacility facility = new NormalizedMunicipalFacility(
                "way/1",
                null,
                MunicipalFacilityType.OFF_STREET,
                "Açık Otopark",
                null,
                38.4,
                27.1,
                null,
                MunicipalAccessClassification.PUBLIC,
                Map.of(),
                "hash");
        service.applyOsmIngest(facilityId, facility, false, TS);

        verify(persistence).deleteProvenanceIfSourceOwns(facilityId, "NAME", OSM);
    }

    @Test
    void realNameToNeutralFallbackWithdrawsName() {
        stubOwned(RegistryField.NAME, OSM, "way/1");
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");

        service.applyOsmIngest(facilityId, osmFacility("way/1", "Otopark", null), false, TS);

        verify(persistence).deleteProvenanceIfSourceOwns(facilityId, "NAME", OSM);
        verify(metrics).provenance(
                eq(RegistryField.NAME), eq("withdrawn_stale"), eq("osm"), anyString(), eq("reconcile"));
    }

    @Test
    void fallbackToRealNameCreatesNameProvenance() {
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");

        service.applyOsmIngest(facilityId, osmFacility("way/1", "Gerçek Ad", null), true, TS);

        verify(persistence).upsertProvenance(any());
        verify(metrics).provenance(eq(RegistryField.NAME), eq("selected"), eq("osm"), anyString(), eq("select"));
        verify(persistence, never()).deleteProvenanceIfSourceOwns(eq(facilityId), eq("NAME"), anyString());
    }

    @Test
    void fallbackToFallbackKeepsNameAbsent() {
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");

        service.applyOsmIngest(facilityId, osmFacility("way/1", "Otopark", null), false, TS);

        verify(persistence, never()).deleteProvenanceIfSourceOwns(eq(facilityId), eq("NAME"), anyString());
        verify(metrics, never()).provenance(eq(RegistryField.NAME), eq("selected"), anyString(), anyString(), anyString());
        verify(metrics, never()).provenance(
                eq(RegistryField.NAME), eq("withdrawn_stale"), anyString(), anyString(), anyString());
    }

    @Test
    void invalidTechnicalNameDoesNotRetainStaleName() {
        stubOwned(RegistryField.NAME, OSM, "way/380281246");
        stubOwned(RegistryField.COORDINATES, OSM, "way/380281246");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/380281246");

        // Hosted-beta stale pattern: display became neutral fallback, NAME still present
        service.applyOsmIngest(
                facilityId, osmFacility("way/380281246", "Otopark", null), false, TS);

        verify(persistence).deleteProvenanceIfSourceOwns(facilityId, "NAME", OSM);
    }

    @Test
    void doesNotDeleteAnotherSourcesName() {
        when(persistence.findProvenance(facilityId, "NAME")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(facilityId, "NAME", IZUM, "ufid-9", 3)));
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");

        service.applyOsmIngest(facilityId, osmFacility("way/1", "Otopark", null), false, TS);

        verify(persistence, never()).deleteProvenanceIfSourceOwns(facilityId, "NAME", OSM);
        verify(persistence, never()).deleteProvenanceIfSourceOwns(facilityId, "NAME", IZUM);
        verify(metrics).provenance(
                eq(RegistryField.NAME), eq("skipped_other_source"), eq("osm"), anyString(), eq("reconcile"));
    }

    @Test
    void attributionRemainsSelected() {
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");

        service.applyOsmIngest(facilityId, osmFacility("way/1", "Otopark", null), false, TS);

        verify(persistence, never()).deleteProvenanceIfSourceOwns(facilityId, "ATTRIBUTION", OSM);
        verify(metrics).provenance(
                eq(RegistryField.ATTRIBUTION), eq("unchanged"), eq("osm"), anyString(), eq("select"));
    }

    @Test
    void operatorWithdrawalAffectsOnlySameSource() {
        when(persistence.findProvenance(facilityId, "OPERATOR")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(facilityId, "OPERATOR", OSM, "way/1", 1)));
        when(persistence.findProvenance(facilityId, "NAME")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(facilityId, "NAME", IZUM, "ufid", 1)));
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");

        // OSM record without operator
        service.applyOsmIngest(facilityId, osmFacility("way/1", "Otopark", null), false, TS);

        verify(persistence).deleteProvenanceIfSourceOwns(facilityId, "OPERATOR", OSM);
        verify(persistence, never()).deleteProvenanceIfSourceOwns(eq(facilityId), eq("NAME"), anyString());
    }

    @Test
    void staticCapacityWithdrawalAffectsOnlySameSource() {
        when(persistence.findProvenance(facilityId, "STATIC_CAPACITY")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(facilityId, "STATIC_CAPACITY", OSM, "way/1", 1)));
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");

        service.applyOsmIngest(facilityId, osmFacility("way/1", "Otopark", null), false, TS);

        verify(persistence).deleteProvenanceIfSourceOwns(facilityId, "STATIC_CAPACITY", OSM);
    }

    @Test
    void ambiguousOwnershipIsSkippedNotGuessed() {
        when(persistence.findProvenance(facilityId, "NAME")).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(facilityId, "NAME", "  ", "way/1", 1)));
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");

        service.applyOsmIngest(facilityId, osmFacility("way/1", "Otopark", null), false, TS);

        verify(persistence, never()).deleteProvenanceIfSourceOwns(any(), eq("NAME"), anyString());
        verify(metrics).provenance(
                eq(RegistryField.NAME), eq("skipped_ambiguous"), eq("osm"), anyString(), eq("reconcile"));
    }

    @Test
    void repeatedReconciliationIsIdempotent() {
        stubOwned(RegistryField.COORDINATES, OSM, "way/1");
        stubOwned(RegistryField.ATTRIBUTION, OSM, "way/1");
        // First call: NAME owned → withdraw; second: NAME absent → no-op
        when(persistence.findProvenance(facilityId, "NAME"))
                .thenReturn(Optional.of(
                        new RegistryPersistencePort.ProvenanceRow(facilityId, "NAME", OSM, "way/1", 1)))
                .thenReturn(Optional.empty());

        service.applyOsmIngest(facilityId, osmFacility("way/1", "Otopark", null), false, TS);
        service.applyOsmIngest(facilityId, osmFacility("way/1", "Otopark", null), false, TS);

        verify(persistence, times(1)).deleteProvenanceIfSourceOwns(facilityId, "NAME", OSM);
    }

    private void stubOwned(RegistryField field, String sourceKey, String recordId) {
        when(persistence.findProvenance(facilityId, field.name())).thenReturn(Optional.of(
                new RegistryPersistencePort.ProvenanceRow(facilityId, field.name(), sourceKey, recordId, 1L)));
    }

    private static NormalizedMunicipalFacility osmFacility(String externalId, String display, String operator) {
        return new NormalizedMunicipalFacility(
                externalId,
                operator,
                null,
                display,
                null,
                38.4,
                27.1,
                null,
                MunicipalAccessClassification.PUBLIC,
                Map.of(),
                "hash-" + externalId);
    }
}
