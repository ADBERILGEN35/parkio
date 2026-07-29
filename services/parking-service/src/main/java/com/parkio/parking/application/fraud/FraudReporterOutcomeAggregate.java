package com.parkio.parking.application.fraud;

import java.time.Instant;
import java.util.UUID;

/** Bounded reporter outcome aggregate for fraud feature construction. */
public record FraudReporterOutcomeAggregate(
        UUID reporterUserId,
        Instant windowStart,
        Instant windowEnd,
        UUID watermarkOutcomeRecordId,
        Instant watermarkEvaluatedAt,
        int eligibleContributionCount,
        int directConfirmedIncorrectCount,
        int likelyIncorrectCount,
        int confirmedCorrectCount,
        int unknownCount,
        int expiredWithoutEvidenceCount) {
}
