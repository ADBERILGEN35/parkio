package com.parkio.parking.application;

import com.parkio.parking.application.port.RegistryPersistencePort;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.registry.FieldProvenanceSelection;
import com.parkio.parking.externalsource.registry.IngestFieldProvenancePolicy;
import com.parkio.parking.externalsource.registry.IngestFieldProvenancePolicy.SuppliedField;
import com.parkio.parking.externalsource.registry.RegistryField;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DATA-WP-10/14: selects field provenance on municipal ingest and withdraws stale
 * same-source selections when the accepted record no longer supplies the field.
 * Never overwrites or deletes another source's selection. Publication remains separately gated.
 */
@Service
public class FieldProvenanceApplicationService {
    public enum IngestOutcome {
        SELECTED,
        UPDATED,
        UNCHANGED,
        WITHDRAWN_STALE,
        SKIPPED_OTHER_SOURCE,
        SKIPPED_AMBIGUOUS,
        SKIPPED_DISABLED,
        FAILED
    }

    private static final String OPERATION_SELECT = "select";
    private static final String OPERATION_RECONCILE = "reconcile";

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
        metrics.provenance(
                selection.field(),
                "selected",
                MunicipalSourceIdentity.familyOf(selection.sourceKey()),
                IngestFieldProvenancePolicy.POLICY_VERSION,
                OPERATION_SELECT);
    }

    /**
     * Apply allow-listed ingest provenance for one facility, then withdraw same-source
     * selections for allow-listed fields absent from this accepted record (DATA-WP-14).
     * Idempotent; never mutates foreign ownership.
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
        Objects.requireNonNull(suppliedFields, "suppliedFields");

        String sourceFamily = MunicipalSourceIdentity.familyOf(sourceKey);
        String policyVersion = IngestFieldProvenancePolicy.POLICY_VERSION;

        if (!properties.isProvenanceIngestWriteEnabled()) {
            // Disables both selection and same-source withdrawal (shared ingest-write kill-switch).
            for (SuppliedField ignored : suppliedFields) {
                metrics.provenance(
                        ignored.field(), "skipped_disabled", sourceFamily, policyVersion, OPERATION_SELECT);
            }
            return;
        }

        Set<RegistryField> supplied = suppliedFields.stream()
                .map(SuppliedField::field)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(RegistryField.class)));

        Instant selectedAt = fetchTimestamp;
        for (SuppliedField field : suppliedFields) {
            applyOne(
                    facilityId,
                    field.field(),
                    sourceKey,
                    sourceRecordId,
                    fetchTimestamp,
                    selectionReason,
                    selectedAt,
                    sourceFamily,
                    policyVersion);
        }

        for (RegistryField field : reconcilableFields()) {
            if (supplied.contains(field)) {
                continue;
            }
            reconcileAbsent(facilityId, field, sourceKey, sourceFamily, policyVersion);
        }
    }

    public void applyIzumIngest(
            UUID facilityId, NormalizedMunicipalFacility facility, Instant fetchTimestamp) {
        applyIngestSelections(
                facilityId,
                MunicipalSourceIdentity.IZUM,
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
                MunicipalSourceIdentity.OSM,
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
            Instant selectedAt,
            String sourceFamily,
            String policyVersion) {
        Optional<RegistryPersistencePort.ProvenanceRow> existing =
                persistence.findProvenance(facilityId, field.name());
        if (existing.isPresent()) {
            RegistryPersistencePort.ProvenanceRow row = existing.get();
            if (row.sourceKey() == null || row.sourceKey().isBlank()) {
                metrics.provenance(
                        field, "skipped_ambiguous", sourceFamily, policyVersion, OPERATION_SELECT);
                return IngestOutcome.SKIPPED_AMBIGUOUS;
            }
            if (!sourceKey.equals(row.sourceKey())) {
                metrics.provenance(
                        field, "skipped_other_source", sourceFamily, policyVersion, OPERATION_SELECT);
                return IngestOutcome.SKIPPED_OTHER_SOURCE;
            }
            if (sourceRecordId.equals(row.sourceRecordId())) {
                metrics.provenance(
                        field, "unchanged", sourceFamily, policyVersion, OPERATION_SELECT);
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
            metrics.provenance(field, "updated", sourceFamily, policyVersion, OPERATION_SELECT);
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
        metrics.provenance(field, "selected", sourceFamily, policyVersion, OPERATION_SELECT);
        return IngestOutcome.SELECTED;
    }

    private IngestOutcome reconcileAbsent(
            UUID facilityId,
            RegistryField field,
            String sourceKey,
            String sourceFamily,
            String policyVersion) {
        Optional<RegistryPersistencePort.ProvenanceRow> existing =
                persistence.findProvenance(facilityId, field.name());
        if (existing.isEmpty()) {
            return IngestOutcome.UNCHANGED;
        }
        RegistryPersistencePort.ProvenanceRow row = existing.get();
        if (row.sourceKey() == null || row.sourceKey().isBlank()) {
            metrics.provenance(
                    field, "skipped_ambiguous", sourceFamily, policyVersion, OPERATION_RECONCILE);
            return IngestOutcome.SKIPPED_AMBIGUOUS;
        }
        if (!sourceKey.equals(row.sourceKey())) {
            metrics.provenance(
                    field, "skipped_other_source", sourceFamily, policyVersion, OPERATION_RECONCILE);
            return IngestOutcome.SKIPPED_OTHER_SOURCE;
        }
        boolean deleted = persistence.deleteProvenanceIfSourceOwns(facilityId, field.name(), sourceKey);
        if (!deleted) {
            metrics.provenance(field, "failed", sourceFamily, policyVersion, OPERATION_RECONCILE);
            return IngestOutcome.FAILED;
        }
        metrics.provenance(
                field, "withdrawn_stale", sourceFamily, policyVersion, OPERATION_RECONCILE);
        return IngestOutcome.WITHDRAWN_STALE;
    }

    private static Set<RegistryField> reconcilableFields() {
        EnumSet<RegistryField> fields = EnumSet.noneOf(RegistryField.class);
        for (String name : IngestFieldProvenancePolicy.INGEST_FIELD_ALLOWLIST) {
            fields.add(RegistryField.valueOf(name));
        }
        return fields;
    }
}
