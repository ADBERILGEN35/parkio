package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.OccupancyFreshnessPolicy;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
import com.parkio.parking.infrastructure.config.PublicExploreProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bounded IZUM-only anonymous public discovery. Visible municipal rows are capped
 * server-side; community exposure is aggregate-count only with a privacy threshold.
 */
public class PublicExploreQueryService {
    public static final double CENTER_LATITUDE = 38.4237;
    public static final double CENTER_LONGITUDE = 27.1428;
    /** Default and hard ceiling for anonymous radius (certified public scope). */
    public static final int DEFAULT_RADIUS_METERS = 5_000;
    public static final int MAX_RADIUS_METERS = 5_000;
    public static final int DEFAULT_LIMIT = 6;
    public static final int MAX_LIMIT = 6;
    /** Below this, communitySpotCountInScope is suppressed ({@code null}). */
    public static final int COMMUNITY_PUBLIC_MIN_COUNT = 3;

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
            String attribution) {}

    public record DiscoveryQuery(Double latitude, Double longitude, Integer radiusMeters, Integer limit) {}

    public record DiscoveryResult(
            List<FacilityView> facilities,
            long municipalTotalInScope,
            long municipalHiddenCount,
            Integer communitySpotCountInScope) {}

    private final MunicipalFacilityRepository facilities;
    private final MunicipalOccupancySnapshotRepository snapshots;
    private final ParkingSpotRepository spots;
    private final PublicExploreProperties properties;
    private final Clock clock;

    public PublicExploreQueryService(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            ParkingSpotRepository spots,
            PublicExploreProperties properties,
            Clock clock) {
        this.facilities = facilities;
        this.snapshots = snapshots;
        this.spots = spots;
        this.properties = properties;
        this.clock = clock;
    }

    public DiscoveryResult discover(DiscoveryQuery query) {
        if (!properties.isIzumAllowed()) {
            return new DiscoveryResult(List.of(), 0L, 0L, null);
        }
        ResolvedScope scope = resolveScope(query);
        long municipalTotal = facilities.countPublicExploreIzumNearby(
                scope.latitude(), scope.longitude(), scope.radiusMeters());
        List<FacilityView> visible = facilities
                .publicExploreIzumNearby(
                        scope.latitude(), scope.longitude(), scope.radiusMeters(), scope.limit())
                .stream()
                .limit(scope.limit())
                .map(this::project)
                .toList();
        long hidden = Math.max(municipalTotal - visible.size(), 0L);
        Integer community = suppressCommunityBelowThreshold(spots.countNearbyVisible(
                scope.latitude(), scope.longitude(), scope.radiusMeters()));
        return new DiscoveryResult(visible, municipalTotal, hidden, community);
    }

    static Integer suppressCommunityBelowThreshold(long rawCount) {
        if (rawCount < COMMUNITY_PUBLIC_MIN_COUNT) {
            return null;
        }
        if (rawCount > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) rawCount;
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

    private FacilityView project(MunicipalFacilityRepository.Facility facility) {
        MunicipalOccupancyFreshness freshness = MunicipalOccupancyFreshness.UNAVAILABLE;
        Integer availableSpaces = null;
        Integer capacityTotal = facility.capacityTotal();
        Instant dataUpdatedAt = null;

        var snapshot = snapshots.latestForFacilityAndSourceKey(
                facility.id(), MunicipalSourceIdentity.IZUM);
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
                ParkingProviderCatalog.IZUM_DISPLAY_NAME,
                ParkingProviderCatalog.IZUM_ATTRIBUTION);
    }

    private record ResolvedScope(double latitude, double longitude, int radiusMeters, int limit) {}
}
