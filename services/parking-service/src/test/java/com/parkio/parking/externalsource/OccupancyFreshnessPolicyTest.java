package com.parkio.parking.externalsource;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OccupancyFreshnessPolicyTest {
    private final OccupancyFreshnessPolicy policy =
            new OccupancyFreshnessPolicy(Duration.ofMinutes(2), Duration.ofMinutes(5));
    private final Instant fetched = Instant.parse("2026-07-30T06:00:00Z");

    @Test void classifiesLiveAgingAndStale() {
        assertThat(policy.classify(null, fetched, fetched.plusSeconds(30), true, true))
                .isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(policy.classify(null, fetched, fetched.plusSeconds(180), true, true))
                .isEqualTo(MunicipalOccupancyFreshness.AGING);
        assertThat(policy.classify(null, fetched, fetched.plusSeconds(300), true, true))
                .isEqualTo(MunicipalOccupancyFreshness.STALE);
    }

    @Test void includesSourceAgeAndHandlesUnavailable() {
        assertThat(policy.classify(280L, fetched, fetched.plusSeconds(30), true, true))
                .isEqualTo(MunicipalOccupancyFreshness.STALE);
        assertThat(policy.classify(null, null, fetched, true, false))
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(policy.classify(null, fetched, fetched, false, true))
                .isEqualTo(MunicipalOccupancyFreshness.INVALID);
    }
}
