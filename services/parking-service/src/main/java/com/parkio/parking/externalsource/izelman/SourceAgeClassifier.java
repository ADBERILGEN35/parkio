package com.parkio.parking.externalsource.izelman;

import java.time.Duration;
import java.time.Instant;

public final class SourceAgeClassifier {
    private SourceAgeClassifier() {}

    public static SourceAgeClassification classify(
            Instant contentAt, Instant fetchedAt, long agingAfterDays, long historicalAfterDays) {
        if (contentAt == null) {
            return SourceAgeClassification.UNKNOWN;
        }
        if (fetchedAt == null || contentAt.isAfter(fetchedAt) || agingAfterDays < 0
                || historicalAfterDays < agingAfterDays) {
            return SourceAgeClassification.INVALID;
        }
        long ageDays = Duration.between(contentAt, fetchedAt).toDays();
        if (ageDays >= historicalAfterDays) {
            return SourceAgeClassification.HISTORICAL;
        }
        if (ageDays >= agingAfterDays) {
            return SourceAgeClassification.AGING;
        }
        return SourceAgeClassification.CURRENT;
    }
}
