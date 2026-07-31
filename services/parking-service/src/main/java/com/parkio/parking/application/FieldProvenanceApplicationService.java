package com.parkio.parking.application;

import com.parkio.parking.application.port.RegistryPersistencePort;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.registry.FieldProvenanceSelection;
import com.parkio.parking.externalsource.registry.IngestFieldProvenancePolicy;
import com.parkio.parking.externalsource.registry.IngestFieldProvenancePolicy.SuppliedField;
import com.parkio.parking.externalsource.registry.RegistryField;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DATA-WP-10: selects field provenance on municipal ingest via the existing upsert model.
 * Never overwrites another source's selection. Publication remains separately gated.
 */
@Service
public class FieldProvenanceApplicationService {
    public enum IngestOutcome {
        SELECTED,
        UPDATED,
        UNCHANGED,
        SKIPPED_OTHER_SOURCE,
        SKIPPED_DISABLED
    }

    private final RegistryPersistencePort persistence;
    private final RegistryMetrics metrics;
    private final RegistryProperties properties;

    public FieldProvenanceApplicationService(
            RegistryPersistencePort persistence,
            RegistryMetrics metrics,
            RegistryProperties properties) {
        this.persistence = persistence;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Transactional
    public void select(FieldProvenanceSelection selection) {
        persistence.upsertProvenance(selection);
        metrics.provenance(selection.field(), "selected");
    }

    /**
     * Apply allow-listed ingest provenance for one facility. Idempotent; skips foreign ownership.
     */
    @Transactional
    public void applyIngestSelections(
            UUID facilityId,
            String sourceKey,
            String sourceRecordId,
            Instant fetchTimestamp,
            String selectionReason,
            List<SuppliedField> suppliedFields) {
        Objects.requireNonNull(facilityId, "facilityId");
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(fetchTimestamp, "fetchTimestamp");
        Objects.requireNonNull(selectionReason, "selectionReason");
        if (!properties.isProvenanceIngestWriteEnabled()) {
            for (SuppliedField ignored : suppliedFields) {
                metrics.provenance(ignored.field(), "skipped_disabled");
            }
            return;
        }
        Instant selectedAt = fetchTimestamp;
        for (SuppliedField supplied : suppliedFields) {
            applyOne(
                    facilityId,
                    supplied.field(),
                    sourceKey,
                    sourceRecordId,
                    fetchTimestamp,
                    selectionReason,
                    selectedAt);
        }
    }

    public void applyIzumIngest(
            UUID facilityId, NormalizedMunicipalFacility facility, Instant fetchTimestamp) {
        applyIngestSelections(
                facilityId,
                com.parkio.parking.externalsource.MunicipalSourceIdentity.IZUM,
                facility.externalId(),
                fetchTimestamp,
                IngestFieldProvenancePolicy.REASON_IZUM_SYNC,
                IngestFieldProvenancePolicy.forIzumFacility(facility));
    }

    public void applyOsmIngest(
            UUID facilityId,
            NormalizedMunicipalFacility facility,
            boolean osmNameTagPresent,
            Instant fetchTimestamp) {
        applyIngestSelections(
                facilityId,
                com.parkio.parking.externalsource.MunicipalSourceIdentity.OSM,
                facility.externalId(),
                fetchTimestamp,
                IngestFieldProvenancePolicy.REASON_OSM_IMPORT,
                IngestFieldProvenancePolicy.forOsmFacility(facility, osmNameTagPresent));
    }

    private IngestOutcome applyOne(
            UUID facilityId,
            RegistryField field,
            String sourceKey,
            String sourceRecordId,
            Instant fetchTimestamp,
            String selectionReason,
            Instant selectedAt) {
        Optional<RegistryPersistencePort.ProvenanceRow> existing =
                persistence.findProvenance(facilityId, field.name());
        if (existing.isPresent()) {
            RegistryPersistencePort.ProvenanceRow row = existing.get();
            if (!sourceKey.equals(row.sourceKey())) {
                metrics.provenance(field, "skipped_other_source");
                return IngestOutcome.SKIPPED_OTHER_SOURCE;
            }
            if (sourceRecordId.equals(row.sourceRecordId())) {
                metrics.provenance(field, "unchanged");
                return IngestOutcome.UNCHANGED;
            }
            persistence.upsertProvenance(new FieldProvenanceSelection(
                    facilityId,
                    field,
                    sourceKey,
                    sourceRecordId,
                    null,
                    fetchTimestamp,
                    FieldProvenanceSelection.SourceAgeClass.CURRENT,
                    IngestFieldProvenancePolicy.CONFIDENCE_SELECTED,
                    selectionReason,
                    selectedAt));
            metrics.provenance(field, "updated");
            return IngestOutcome.UPDATED;
        }
        persistence.upsertProvenance(new FieldProvenanceSelection(
                facilityId,
                field,
                sourceKey,
                sourceRecordId,
                null,
                fetchTimestamp,
                FieldProvenanceSelection.SourceAgeClass.CURRENT,
                IngestFieldProvenancePolicy.CONFIDENCE_SELECTED,
                selectionReason,
                selectedAt));
        metrics.provenance(field, "selected");
        return IngestOutcome.SELECTED;
    }
}
