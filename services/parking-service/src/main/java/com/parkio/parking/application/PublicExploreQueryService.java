package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.application.port.RoadsideDiscoveryQueryPort;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.OccupancyFreshnessPolicy;
import com.parkio.parking.externalsource.PublicExplorePublicationPolicy.ReviewedPublicFamily;
import com.parkio.parking.externalsource.provider.ParkingDataSourceDescriptor;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
import com.parkio.parking.infrastructure.config.PublicExploreProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 *
 * <p>When the reviewed {@link ReviewedPublicFamily#IZELMAN} family is allowlisted,
 * published İZELMAN roadside segments are included as on-street inventory with
 * UNKNOWN access and UNAVAILABLE occupancy (never live spaces).
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

    public static final String IZELMAN_ROADSIDE_SOURCE_LABEL = "İZELMAN roadside";
    public static final String IZELMAN_ROADSIDE_ATTRIBUTION =
            "İzmir Metropolitan Municipality / İZELMAN A.Ş.";

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
    private final RoadsideDiscoveryQueryPort roadside;
    private final PublicExploreProperties properties;
    private final Clock clock;

    public PublicExploreQueryService(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            RoadsideDiscoveryQueryPort roadside,
            PublicExploreProperties properties,
            Clock clock) {
        this.facilities = facilities;
        this.snapshots = snapshots;
        this.roadside = roadside;
        this.properties = properties;
        this.clock = clock;
    }

    public DiscoveryResult discover(DiscoveryQuery query) {
        Set<String> allowedKeys = properties.resolvedSourceKeys();
        if (allowedKeys.isEmpty()) {
            return new DiscoveryResult(List.of(), 0L, 0L, null);
        }
        ResolvedScope scope = resolveScope(query);
        long facilityTotal = facilities.countPublicExploreNearby(
                scope.latitude(), scope.longitude(), scope.radiusMeters(), allowedKeys);
        boolean includeRoadside = properties.reviewedFamilies().contains(ReviewedPublicFamily.IZELMAN);
        long roadsideTotal = includeRoadside
                ? roadside.countNearby(scope.latitude(), scope.longitude(), scope.radiusMeters())
                : 0L;
        long municipalTotal = facilityTotal + roadsideTotal;

        List<Scored> scored = new ArrayList<>();
        facilities
                .publicExploreNearby(
                        scope.latitude(),
                        scope.longitude(),
                        scope.radiusMeters(),
                        scope.limit(),
                        allowedKeys)
                .stream()
                .map(facility -> project(facility, allowedKeys))
                .forEach(view -> scored.add(new Scored(view, distanceMeters(
                        scope.latitude(), scope.longitude(), view.latitude(), view.longitude()))));
        if (includeRoadside) {
            roadside.nearby(scope.latitude(), scope.longitude(), scope.radiusMeters(), scope.limit())
                    .stream()
                    .map(PublicExploreQueryService::projectRoadside)
                    .forEach(view -> scored.add(new Scored(view, distanceMeters(
                            scope.latitude(), scope.longitude(), view.latitude(), view.longitude()))));
        }
        scored.sort(Comparator.comparingDouble(Scored::distanceMeters));
        List<FacilityView> visible = scored.stream()
                .limit(scope.limit())
                .map(Scored::view)
                .toList();
        long hidden = Math.max(municipalTotal - visible.size(), 0L);
        return new DiscoveryResult(visible, municipalTotal, hidden, withholdCommunityAggregate());
    }

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

    static FacilityView projectRoadside(RoadsideDiscoveryQueryPort.RoadsideSegment segment) {
        return new FacilityView(
                segment.id(),
                segment.displayName(),
                "İZELMAN A.Ş.",
                MunicipalFacilityType.ON_STREET,
                segment.addressText(),
                segment.latitude(),
                segment.longitude(),
                segment.capacityTotal(),
                null,
                MunicipalOccupancyFreshness.UNAVAILABLE,
                segment.updatedAt(),
                IZELMAN_ROADSIDE_SOURCE_LABEL,
                IZELMAN_ROADSIDE_ATTRIBUTION,
                MunicipalAccessClassification.UNKNOWN);
    }

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

    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private record ResolvedScope(double latitude, double longitude, int radiusMeters, int limit) {}

    private record Scored(FacilityView view, double distanceMeters) {}
}
