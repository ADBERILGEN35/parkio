package com.parkio.parking.decision.evidence;

import com.parkio.parking.decision.assessment.ReasonCode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One normalized observation about a parking-spot submission.
 *
 * <p>Immutable. Does not embed provider-specific raw payloads. Strength is an
 * integer on the 0-100 scale (same numeric range as {@code EvidenceScore}, but
 * this is an observation weight — not a computed Evidence Score).
 *
 * <p>Invariants:
 * <ul>
 *   <li>type, source, polarity, observedAt are required</li>
 *   <li>strength is in {@code [0, 100]}</li>
 *   <li>{@link EvidencePolarity#ABSENT} records missing signal; it is not negative evidence</li>
 *   <li>optional reason / sourceReference carry provenance only</li>
 * </ul>
 */
public final class EvidenceItem {

    private final EvidenceType type;
    private final EvidenceSource source;
    private final EvidencePolarity polarity;
    private final int strength;
    private final Instant observedAt;
    private final ReasonCode reasonCode;
    private final String sourceReference;

    private EvidenceItem(
            EvidenceType type,
            EvidenceSource source,
            EvidencePolarity polarity,
            int strength,
            Instant observedAt,
            ReasonCode reasonCode,
            String sourceReference) {
        this.type = Objects.requireNonNull(type, "type");
        this.source = Objects.requireNonNull(source, "source");
        this.polarity = Objects.requireNonNull(polarity, "polarity");
        if (strength < 0 || strength > 100) {
            throw new IllegalArgumentException("strength must be between 0 and 100 inclusive");
        }
        this.strength = strength;
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
        this.reasonCode = reasonCode;
        this.sourceReference = normalizeOptionalText(sourceReference, "sourceReference");
    }

    public static EvidenceItem of(
            EvidenceType type,
            EvidenceSource source,
            EvidencePolarity polarity,
            int strength,
            Instant observedAt) {
        return new EvidenceItem(type, source, polarity, strength, observedAt, null, null);
    }

    public static EvidenceItem of(
            EvidenceType type,
            EvidenceSource source,
            EvidencePolarity polarity,
            int strength,
            Instant observedAt,
            ReasonCode reasonCode,
            String sourceReference) {
        return new EvidenceItem(type, source, polarity, strength, observedAt, reasonCode, sourceReference);
    }

    public EvidenceType type() {
        return type;
    }

    public EvidenceSource source() {
        return source;
    }

    public EvidencePolarity polarity() {
        return polarity;
    }

    public int strength() {
        return strength;
    }

    public Instant observedAt() {
        return observedAt;
    }

    public Optional<ReasonCode> reasonCode() {
        return Optional.ofNullable(reasonCode);
    }

    public Optional<String> sourceReference() {
        return Optional.ofNullable(sourceReference);
    }

    /**
     * Stable lexicographic key used by {@link EvidenceVector} canonical ordering and by
     * assessment evidence references ({@code EvidenceReference}).
     */
    public String canonicalKey() {
        return type.name()
                + '|'
                + source.name()
                + '|'
                + polarity.name()
                + '|'
                + observedAt
                + '|'
                + (reasonCode == null ? "" : reasonCode.value())
                + '|'
                + (sourceReference == null ? "" : sourceReference)
                + '|'
                + strength;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvidenceItem that)) {
            return false;
        }
        return strength == that.strength
                && type == that.type
                && source == that.source
                && polarity == that.polarity
                && observedAt.equals(that.observedAt)
                && Objects.equals(reasonCode, that.reasonCode)
                && Objects.equals(sourceReference, that.sourceReference);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, source, polarity, strength, observedAt, reasonCode, sourceReference);
    }

    private static String normalizeOptionalText(String value, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 256) {
            throw new IllegalArgumentException(field + " must be at most 256 characters");
        }
        return trimmed;
    }
}