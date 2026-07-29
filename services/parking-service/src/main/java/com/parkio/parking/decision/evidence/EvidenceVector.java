package com.parkio.parking.decision.evidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of all evidence available for one Decision evaluation.
 *
 * <p>Does not score or decide. Conflicting items are preserved as distinct
 * entries — this type never silently merges observations.
 *
 * <p>Items are stored in deterministic canonical order ({@link EvidenceItem#canonicalKey()}).
 */
public final class EvidenceVector {

    public static final String SCHEMA_VERSION_V1 = "evidence-vector-v1";

    private static final Comparator<EvidenceItem> CANONICAL_ORDER =
            Comparator.comparing(EvidenceItem::canonicalKey);

    private final UUID parkingSpotId;
    private final UUID evaluationId;
    private final Instant collectedAt;
    private final String schemaVersion;
    private final List<EvidenceItem> items;

    private EvidenceVector(
            UUID parkingSpotId,
            UUID evaluationId,
            Instant collectedAt,
            String schemaVersion,
            List<EvidenceItem> items) {
        this.parkingSpotId = Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        this.evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        this.collectedAt = Objects.requireNonNull(collectedAt, "collectedAt");
        this.schemaVersion = requireSchemaVersion(schemaVersion);
        Objects.requireNonNull(items, "items");
        List<EvidenceItem> copy = new ArrayList<>(items.size());
        for (EvidenceItem item : items) {
            if (item == null) {
                throw new IllegalArgumentException("evidence items must not contain null");
            }
            copy.add(item);
        }
        copy.sort(CANONICAL_ORDER);
        this.items = Collections.unmodifiableList(copy);
    }

    public static EvidenceVector of(
            UUID parkingSpotId,
            UUID evaluationId,
            Instant collectedAt,
            List<EvidenceItem> items) {
        return new EvidenceVector(parkingSpotId, evaluationId, collectedAt, SCHEMA_VERSION_V1, items);
    }

    public static EvidenceVector of(
            UUID parkingSpotId,
            UUID evaluationId,
            Instant collectedAt,
            String schemaVersion,
            List<EvidenceItem> items) {
        return new EvidenceVector(parkingSpotId, evaluationId, collectedAt, schemaVersion, items);
    }

    public UUID parkingSpotId() {
        return parkingSpotId;
    }

    public UUID evaluationId() {
        return evaluationId;
    }

    public Instant collectedAt() {
        return collectedAt;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public List<EvidenceItem> items() {
        return items;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvidenceVector that)) {
            return false;
        }
        return parkingSpotId.equals(that.parkingSpotId)
                && evaluationId.equals(that.evaluationId)
                && collectedAt.equals(that.collectedAt)
                && schemaVersion.equals(that.schemaVersion)
                && items.equals(that.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parkingSpotId, evaluationId, collectedAt, schemaVersion, items);
    }

    private static String requireSchemaVersion(String schemaVersion) {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        String trimmed = schemaVersion.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("schemaVersion must not be blank");
        }
        return trimmed;
    }
}