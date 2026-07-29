package com.parkio.parking.decision.score;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Actor-level trust input for Decision evaluation, scale {@code 0.00..1.00}.
 *
 * <p>This is <strong>not</strong> the gamification-service {@code TrustScore} aggregate
 * ({@code int 0..100}). Adapters MAY convert between representations later; the Decision
 * domain keeps a distinct type and scale so Evidence Score and Trust remain separable.
 *
 * <p>Stored as {@link BigDecimal} with scale 2 for deterministic equality.
 * Unknown trust MUST be {@code Optional.empty()}, never {@code 0.00}.
 *
 * <p>Contains no trust-update algorithm.
 */
public final class TrustScore {

    public static final BigDecimal MIN = new BigDecimal("0.00");
    public static final BigDecimal MAX = new BigDecimal("1.00");

    private final BigDecimal value;

    private TrustScore(BigDecimal value) {
        this.value = value;
    }

    public static TrustScore of(BigDecimal raw) {
        Objects.requireNonNull(raw, "value");
        BigDecimal normalized = raw.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(MIN) < 0 || normalized.compareTo(MAX) > 0) {
            throw new IllegalArgumentException(
                    "TrustScore must be between 0.00 and 1.00 inclusive, got " + raw);
        }
        return new TrustScore(normalized);
    }

    public static TrustScore of(String raw) {
        Objects.requireNonNull(raw, "value");
        try {
            return of(new BigDecimal(raw.trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("TrustScore must be a decimal: " + raw, ex);
        }
    }

    public BigDecimal value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrustScore that)) {
            return false;
        }
        return value.compareTo(that.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "TrustScore[" + value.toPlainString() + "]";
    }
}