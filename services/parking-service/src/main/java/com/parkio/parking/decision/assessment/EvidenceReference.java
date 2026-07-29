package com.parkio.parking.decision.assessment;

import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidenceSource;
import com.parkio.parking.decision.evidence.EvidenceType;
import java.util.Objects;
import java.util.Optional;

/**
 * Compact, deterministic reference to an {@link EvidenceItem} without embedding
 * provider payloads.
 *
 * <p>Identity is the evidence {@link EvidenceItem#canonicalKey()}. Optional type,
 * source, and reason code support audit readability when the full vector is not
 * co-located.
 */
public final class EvidenceReference {

    private final String canonicalKey;
    private final EvidenceType type;
    private final EvidenceSource source;
    private final ReasonCode reasonCode;

    private EvidenceReference(
            String canonicalKey, EvidenceType type, EvidenceSource source, ReasonCode reasonCode) {
        this.canonicalKey = requireCanonicalKey(canonicalKey);
        this.type = Objects.requireNonNull(type, "type");
        this.source = Objects.requireNonNull(source, "source");
        this.reasonCode = reasonCode;
    }

    public static EvidenceReference from(EvidenceItem item) {
        Objects.requireNonNull(item, "item");
        return new EvidenceReference(
                item.canonicalKey(),
                item.type(),
                item.source(),
                item.reasonCode().orElse(null));
    }

    public static EvidenceReference of(
            String canonicalKey, EvidenceType type, EvidenceSource source, ReasonCode reasonCode) {
        return new EvidenceReference(canonicalKey, type, source, reasonCode);
    }

    public String canonicalKey() {
        return canonicalKey;
    }

    public EvidenceType type() {
        return type;
    }

    public EvidenceSource source() {
        return source;
    }

    public Optional<ReasonCode> reasonCode() {
        return Optional.ofNullable(reasonCode);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvidenceReference that)) {
            return false;
        }
        return canonicalKey.equals(that.canonicalKey);
    }

    @Override
    public int hashCode() {
        return canonicalKey.hashCode();
    }

    private static String requireCanonicalKey(String canonicalKey) {
        Objects.requireNonNull(canonicalKey, "canonicalKey");
        String trimmed = canonicalKey.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("canonicalKey must not be blank");
        }
        if (trimmed.length() > 512) {
            throw new IllegalArgumentException("canonicalKey must be at most 512 characters");
        }
        return trimmed;
    }
}