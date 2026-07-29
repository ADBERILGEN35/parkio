package com.parkio.parking.fraud;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Frozen aggregate features consumed by {@link FraudEngine}. */
public record FraudFeatureVector(
        FraudSubject subject,
        FraudDomain domain,
        Instant windowStart,
        Instant windowEnd,
        UUID sourceWatermarkOutcomeRecordId,
        Instant sourceWatermarkEvaluatedAt,
        int eligibleContributionCount,
        int directConfirmedIncorrectCount,
        int likelyIncorrectCount,
        int confirmedCorrectCount,
        int unknownCount,
        int expiredWithoutEvidenceCount,
        String aggregationVersion) {

    public FraudFeatureVector {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(sourceWatermarkOutcomeRecordId, "sourceWatermarkOutcomeRecordId");
        Objects.requireNonNull(sourceWatermarkEvaluatedAt, "sourceWatermarkEvaluatedAt");
        Objects.requireNonNull(aggregationVersion, "aggregationVersion");
        requireNonNegative(eligibleContributionCount);
        requireNonNegative(directConfirmedIncorrectCount);
        requireNonNegative(likelyIncorrectCount);
        requireNonNegative(confirmedCorrectCount);
        requireNonNegative(unknownCount);
        requireNonNegative(expiredWithoutEvidenceCount);
        if (windowEnd.isBefore(windowStart)) {
            throw new IllegalArgumentException("windowEnd must not precede windowStart");
        }
    }

    public FraudEvidenceVolume evidenceVolume() {
        return FraudEvidenceVolume.of(eligibleContributionCount);
    }

    private static void requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
    }
}
