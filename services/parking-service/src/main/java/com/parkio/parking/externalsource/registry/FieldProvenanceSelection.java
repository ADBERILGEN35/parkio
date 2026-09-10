package com.parkio.parking.externalsource.registry;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FieldProvenanceSelection(
        UUID facilityId,
        RegistryField field,
        String sourceKey,
        String sourceRecordId,
        Instant sourceContentTimestamp,
        Instant fetchTimestamp,
        SourceAgeClass sourceAgeClass,
        String confidenceOrReviewState,
        String selectionReason,
        Instant selectedAt) {

    public FieldProvenanceSelection {
        Objects.requireNonNull(facilityId, "facilityId");
        Objects.requireNonNull(field, "field");
        sourceKey = requireText(sourceKey, "sourceKey");
        sourceRecordId = requireText(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(fetchTimestamp, "fetchTimestamp");
        Objects.requireNonNull(sourceAgeClass, "sourceAgeClass");
        confidenceOrReviewState = requireText(confidenceOrReviewState, "confidenceOrReviewState");
        selectionReason = requireText(selectionReason, "selectionReason");
        Objects.requireNonNull(selectedAt, "selectedAt");
    }

    public enum SourceAgeClass {
        CURRENT,
        AGING,
        HISTORICAL,
        UNKNOWN
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
