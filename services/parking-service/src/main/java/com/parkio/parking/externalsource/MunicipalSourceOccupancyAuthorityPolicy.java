package com.parkio.parking.externalsource;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Canonical source-level occupancy authority (DATA-WP-17).
 *
 * <p>Separates operational source health from occupancy freshness:
 * <ul>
 *   <li>operational state — whether the source is functioning for its operating mode</li>
 *   <li>occupancy freshness — whether the source currently supplies valid live occupancy</li>
 * </ul>
 *
 * <p>Only İZUM may contribute occupancy. OSM and all İZELMAN sources are always
 * {@link MunicipalOccupancyFreshness#UNAVAILABLE} at source level, regardless of import
 * success, publication, scheduler flags, facility counts or operational health.
 *
 * <p>Facility projection must continue to use
 * {@link MunicipalSourcePublicationPolicy#mayContributeLiveOccupancy(java.util.Set)}; this
 * policy is the source-key form of the same authority rule.
 */
public final class MunicipalSourceOccupancyAuthorityPolicy {

    public boolean mayContributeOccupancy(String sourceKey) {
        return MunicipalSourceIdentity.isIzum(sourceKey);
    }

    /**
     * Source-level freshness for quality-report / health / metrics.
     *
     * @param latestFetchedAt latest occupancy snapshot {@code fetched_at} for the source, or null
     * @param sourceAgeSeconds optional transport/source age seconds from the observation
     * @param valid whether the latest observation is contract-valid
     */
    public MunicipalOccupancyFreshness classify(
            String sourceKey,
            Instant latestFetchedAt,
            Long sourceAgeSeconds,
            boolean valid,
            long agingAfterSeconds,
            long staleAfterSeconds,
            Instant now) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(now, "now");
        if (!mayContributeOccupancy(sourceKey)) {
            return MunicipalOccupancyFreshness.UNAVAILABLE;
        }
        if (latestFetchedAt == null) {
            return MunicipalOccupancyFreshness.UNAVAILABLE;
        }
        Duration aging = Duration.ofSeconds(Math.max(0L, agingAfterSeconds));
        Duration stale = Duration.ofSeconds(Math.max(aging.toSeconds(), staleAfterSeconds));
        return new OccupancyFreshnessPolicy(aging, stale)
                .classify(sourceAgeSeconds, latestFetchedAt, now, valid, true);
    }
}
