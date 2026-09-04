package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
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
import java.util.Optional;
import java.util.UUID;

/** Fixed-shape, IZUM-only query used exclusively by anonymous public explore. */
public class PublicExploreQueryService {
    public static final double CENTER_LATITUDE = 38.4237;
    public static final double CENTER_LONGITUDE = 27.1428;
    public static final int RADIUS_METERS = 5_000;
    public static final int MAX_RESULTS = 20;

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

    public List<FacilityView> list() {
        if (!properties.isIzumAllowed()) {
            return List.of();
        }
        return facilities.publicExploreIzumNearby(
                        CENTER_LATITUDE, CENTER_LONGITUDE, RADIUS_METERS, MAX_RESULTS)
                .stream()
                .limit(MAX_RESULTS)
                .map(this::project)
                .toList();
    }

    public Optional<FacilityView> findById(UUID id) {
        if (!properties.isIzumAllowed()) {
            return Optional.empty();
        }
        return facilities.findPublicExploreIzumById(
                        id, CENTER_LATITUDE, CENTER_LONGITUDE, RADIUS_METERS)
                .map(this::project);
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
}
