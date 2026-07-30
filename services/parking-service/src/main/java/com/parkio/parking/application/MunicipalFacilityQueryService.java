package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.OccupancyFreshnessPolicy;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MunicipalFacilityQueryService {
    public record FacilityView(
            UUID id, String displayName, String operatorName, MunicipalFacilityType facilityType,
            String addressText, double latitude, double longitude, Integer capacityTotal,
            Integer availableSpaces, MunicipalOccupancyFreshness freshness, String attribution,
            String sourceLabel, Instant lastUpdatedAt) {}

    private final MunicipalFacilityRepository facilities;
    private final MunicipalOccupancySnapshotRepository snapshots;
    private final MunicipalSourceProperties municipalProperties;
    private final Clock clock;

    public MunicipalFacilityQueryService(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            MunicipalSourceProperties municipalProperties,
            Clock clock) {
        this.facilities = facilities;
        this.snapshots = snapshots;
        this.municipalProperties = municipalProperties;
        this.clock = clock;
    }

    public List<FacilityView> nearby(double lat, double lng, int radiusMeters, int limit) {
        validate(lat, lng, radiusMeters, limit);
        return facilities.nearby(lat, lng, radiusMeters, limit).stream()
                .filter(this::isDiscoverable)
                .map(this::project)
                .toList();
    }

    public Optional<FacilityView> findById(UUID id) {
        return facilities.findById(id).filter(this::isDiscoverable).map(this::project);
    }

    private FacilityView project(MunicipalFacilityRepository.Facility facility) {
        var snapshot = snapshots.latestForFacility(facility.id());
        MunicipalOccupancyFreshness freshness = MunicipalOccupancyFreshness.UNAVAILABLE;
        Integer available = null;
        Instant lastUpdated = null;
        Integer capacity = facility.capacityTotal();
        // OSM never contributes live occupancy; ignore any snapshot on OSM-attributed rows.
        boolean osmDerived = facility.attribution() != null
                && facility.attribution().contains("OpenStreetMap");
        if (!osmDerived && snapshot.isPresent()) {
            var value = snapshot.get();
            freshness = new OccupancyFreshnessPolicy(
                    Duration.ofSeconds(facility.agingAfterSeconds()),
                    Duration.ofSeconds(facility.staleAfterSeconds()))
                    .classify(value.sourceAgeSeconds(), value.fetchedAt(), clock.instant(), value.valid(), true);
            if (freshness == MunicipalOccupancyFreshness.LIVE
                    || freshness == MunicipalOccupancyFreshness.AGING) {
                available = value.availableSpaces();
            }
            if (value.capacityTotal() != null) {
                capacity = value.capacityTotal();
            }
            lastUpdated = value.fetchedAt();
        }
        return new FacilityView(
                facility.id(),
                facility.displayName(),
                facility.operatorName(),
                facility.facilityType(),
                facility.addressText(),
                facility.latitude(),
                facility.longitude(),
                capacity,
                available,
                freshness,
                facility.attribution(),
                facility.sourceLabel(),
                lastUpdated);
    }

    /** OSM facilities stay hidden until publication-enabled is explicitly turned on. */
    private boolean isDiscoverable(MunicipalFacilityRepository.Facility facility) {
        if (facility.attribution() != null
                && facility.attribution().contains("OpenStreetMap")
                && !municipalProperties.getOsm().isPublicationEnabled()) {
            return false;
        }
        return true;
    }

    private static void validate(double lat, double lng, int radius, int limit) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90
                || !Double.isFinite(lng) || lng < -180 || lng > 180
                || radius <= 0 || radius > 50000 || limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("invalid nearby query");
        }
    }
}