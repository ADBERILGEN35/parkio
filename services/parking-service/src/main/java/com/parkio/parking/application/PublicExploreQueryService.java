package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.OccupancyFreshnessPolicy;
import com.parkio.parking.externalsource.provider.ParkingDataSourceDescriptor;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
import com.parkio.parking.infrastructure.config.PublicExploreProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded anonymous public discovery across reviewed municipal publication sources.
 * Visible municipal rows are capped server-side. Community parking spots are never
 * published as rows or as spatial aggregates on this path (PA-06 / G06): free
 * center/radius plus an exact count enabled differencing of otherwise undisclosed
 * spots. {@code communitySpotCountInScope} is always {@code null} for anonymous
 * Explore. Source selection comes from validated publication policy, never from
 * anonymous query parameters.
 */
public class PublicExploreQueryService {
    /** Client UX fallback center only; server honors supplied lat/lng when present. */
    public static final double CENTER_LATITUDE = 38.4237;
    public static final double CENTER_LONGITUDE = 27.1428;
    /** Default and hard ceiling for anonymous radius (certified public scope). */
    public static final int DEFAULT_RADIUS_METERS = 5_000;
    public static final int MAX_RADIUS_METERS = 5_000;
    public static final int DEFAULT_LIMIT = 6;
    public static final int MAX_LIMIT = 6;

    public record FacilityView(
            UUID id,
            String displayName,
            String operatorName,
            MunicipalFacilityType facilityType,
            String addressText,
            double latitude,
            double longitude,
            Integer capacityTotal,
            Integer availableSpaces,
            MunicipalOccupancyFreshness availabilityFreshness,
            Instant dataUpdatedAt,
            String sourceLabel,
            String attribution,
            MunicipalAccessClassification accessClassification) {}

    public record DiscoveryQuery(Double latitude, Double longitude, Integer radiusMeters, Integer limit) {}

    public record DiscoveryResult(
            List<FacilityView> facilities,
            long municipalTotalInScope,
            long municipalHiddenCount,
            Integer communitySpotCountInScope) {}

    private final MunicipalFacilityRepository facilities;
    private final MunicipalOccupancySnapshotRepository snapshots;
    private final PublicExploreProperties properties;
    private final Clock clock;

    public PublicExploreQueryService(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            PublicExploreProperties properties,
            Clock clock) {
        this.facilities = facilities;
        this.snapshots = snapshots;
        this.properties = properties;
        this.clock = clock;
    }

    public DiscoveryResult discover(DiscoveryQuery query) {
        Set<String> allowedKeys = properties.resolvedSourceKeys();
        if (allowedKeys.isEmpty()) {
            return new DiscoveryResult(List.of(), 0L, 0L, null);
        }
        ResolvedScope scope = resolveScope(query);
        long municipalTotal = facilities.countPublicExploreNearby(
                scope.latitude(), scope.longitude(), scope.radiusMeters(), allowedKeys);
        List<FacilityView> visible = facilities
                .publicExploreNearby(
                        scope.latitude(),
                        scope.longitude(),
                        scope.radiusMeters(),
                        scope.limit(),
                        allowedKeys)
                .stream()
                .limit(scope.limit())
                .map(facility -> project(facility, allowedKeys))
                .toList();
        long hidden = Math.max(municipalTotal - visible.size(), 0L);
        // PA-06: do not publish community aggregates under free lat/lng/radius.
        // Authenticated clients use separate authorized nearby APIs.
        return new DiscoveryResult(visible, municipalTotal, hidden, withholdCommunityAggregate());
    }

    /**
     * Anonymous Explore never publishes a community count. {@code null} means
     * unavailable/withheld — not a factual zero. Older clients already treat null
     * as “do not show teaser”.
     */
    static Integer withholdCommunityAggregate() {
        return null;
    }

    private ResolvedScope resolveScope(DiscoveryQuery query) {
        boolean hasLat = query.latitude() != null;
        boolean hasLng = query.longitude() != null;
        if (hasLat != hasLng) {
            throw new IllegalArgumentException("lat and lng must be supplied together");
        }
        double lat = hasLat ? query.latitude() : CENTER_LATITUDE;
        double lng = hasLng ? query.longitude() : CENTER_LONGITUDE;
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("latitude must be a finite value between -90 and 90");
        }
        if (!Double.isFinite(lng) || lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException("longitude must be a finite value between -180 and 180");
        }
        int radius = query.radiusMeters() == null ? DEFAULT_RADIUS_METERS : query.radiusMeters();
        if (radius < 1 || radius > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException(
                    "radiusMeters must be between 1 and " + MAX_RADIUS_METERS);
        }
        int limit = query.limit() == null ? DEFAULT_LIMIT : query.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return new ResolvedScope(lat, lng, radius, limit);
    }

    private FacilityView project(MunicipalFacilityRepository.Facility facility, Set<String> allowedKeys) {
        String publishingKey = resolvePublishingSourceKey(facility, allowedKeys);
        ParkingDataSourceDescriptor presentation = ParkingProviderCatalog.find(publishingKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Public explore facility missing reviewed catalog source"));

        MunicipalOccupancyFreshness freshness = MunicipalOccupancyFreshness.UNAVAILABLE;
        Integer availableSpaces = null;
        Integer capacityTotal = facility.capacityTotal();
        Instant dataUpdatedAt = null;

        var snapshot = snapshots.latestForFacilityAndSourceKey(facility.id(), publishingKey);
        if (snapshot.isPresent()) {
            var value = snapshot.get();
            freshness = new OccupancyFreshnessPolicy(
                    Duration.ofSeconds(facility.agingAfterSeconds()),
                    Duration.ofSeconds(facility.staleAfterSeconds()))
                    .classify(value.sourceAgeSeconds(), value.fetchedAt(), clock.instant(), value.valid(), true);
            if (freshness == MunicipalOccupancyFreshness.LIVE
                    || freshness == MunicipalOccupancyFreshness.AGING) {
                availableSpaces = value.availableSpaces();
            }
            if (value.capacityTotal() != null) {
                capacityTotal = value.capacityTotal();
            }
            dataUpdatedAt = value.fetchedAt();
        }

        return new FacilityView(
                facility.id(),
                facility.displayName(),
                facility.operatorName(),
                facility.facilityType(),
                facility.addressText(),
                facility.latitude(),
                facility.longitude(),
                capacityTotal,
                availableSpaces,
                freshness,
                dataUpdatedAt,
                presentation.displayName(),
                presentation.attribution(),
                facility.accessClassification() == null
                        ? MunicipalAccessClassification.UNKNOWN
                        : facility.accessClassification());
    }

    /**
     * Occupancy and attribution bind to the facility's publishing source key from the
     * public query join (linked keys), never a global provider-latest snapshot.
     */
    static String resolvePublishingSourceKey(
            MunicipalFacilityRepository.Facility facility, Set<String> allowedKeys) {
        Set<String> linked = facility.linkedSourceKeys() == null ? Set.of() : facility.linkedSourceKeys();
        if (facility.primarySourceKey() != null
                && allowedKeys.contains(facility.primarySourceKey())
                && (linked.isEmpty() || linked.contains(facility.primarySourceKey()))) {
            return facility.primarySourceKey();
        }
        return linked.stream()
                .filter(allowedKeys::contains)
                .sorted()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Public explore facility has no allowed publishing source key"));
    }

    private record ResolvedScope(double latitude, double longitude, int radiusMeters, int limit) {}
}
