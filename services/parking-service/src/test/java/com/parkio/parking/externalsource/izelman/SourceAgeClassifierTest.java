package com.parkio.parking.externalsource.izelman;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SourceAgeClassifierTest {
    private static final Instant FETCHED = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void classifiesByContentDateNotFetchDate() {
        assertThat(SourceAgeClassifier.classify(Instant.parse("2026-07-29T00:00:00Z"), FETCHED, 180, 730))
                .isEqualTo(SourceAgeClassification.CURRENT);
        assertThat(SourceAgeClassifier.classify(Instant.parse("2024-09-02T00:00:00Z"), FETCHED, 180, 730))
                .isEqualTo(SourceAgeClassification.AGING);
        assertThat(SourceAgeClassifier.classify(Instant.parse("2022-11-25T00:00:00Z"), FETCHED, 180, 730))
                .isEqualTo(SourceAgeClassification.HISTORICAL);
    }

    @Test
    void missingContentDateIsNeverCurrent() {
        assertThat(SourceAgeClassifier.classify(null, FETCHED, 180, 730))
                .isEqualTo(SourceAgeClassification.UNKNOWN);
    }
}
