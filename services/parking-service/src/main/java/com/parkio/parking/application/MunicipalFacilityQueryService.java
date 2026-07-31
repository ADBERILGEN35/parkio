package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSourcePublicationPolicy;
import com.parkio.parking.externalsource.OccupancyFreshnessPolicy;
import com.parkio.parking.externalsource.discovery.DiscoveryDuplicatePresentationPolicy;
import com.parkio.parking.infrastructure.config.IzelmanProperties;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.metrics.DiscoveryDuplicatePresentationMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class MunicipalFacilityQueryService {
    static final String IZUM_SOURCE_LABEL = "Izmir Buyuksehir Belediyesi / IZUM";
    static final String IZUM_ATTRIBUTION =
            "Includes public sector information from Izmir Buyuksehir Belediyesi Acik Veri Portali "
                    + "licensed under Attribution 4.0 International (CC BY 4.0). Parkio is not affiliated "
                    + "with or endorsed by Izmir Municipality or IZELMAN A.S.";
    static final String OSM_SOURCE_LABEL = "OpenStreetMap contributors / Geofabrik GmbH";
    static final String OSM_ATTRIBUTION = "OpenStreetMap contributors";

    public record FacilityView(
            UUID id, String displayName, String operatorName, MunicipalFacilityType facilityType,
            String addressText, double latitude, double longitude, Integer capacityTotal,
            Integer availableSpaces, MunicipalOccupancyFreshness freshness, String attribution,
            String sourceLabel, Instant lastUpdatedAt) {}

    private final MunicipalFacilityRepository facilities;
    private final MunicipalOccupancySnapshotRepository snapshots;
    private final MunicipalSourcePublicationPolicy publicationPolicy;
    private final MunicipalSourceProperties.Discovery discovery;
    private final DiscoveryDuplicatePresentationMetrics metrics;
    private final Clock clock;

    public MunicipalFacilityQueryService(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            MunicipalSourceProperties municipalProperties,
            IzelmanProperties izelmanProperties,
            Clock clock) {
        this(facilities, snapshots, municipalProperties, izelmanProperties, null, clock);
    }

    public MunicipalFacilityQueryService(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            MunicipalSourceProperties municipalProperties,
            IzelmanProperties izelmanProperties,
            DiscoveryDuplicatePresentationMetrics metrics,
            Clock clock) {
        this.facilities = facilities;
        this.snapshots = snapshots;
        this.publicationPolicy = new MunicipalSourcePublicationPolicy(municipalProperties, izelmanProperties);
        this.discovery = municipalProperties.getDiscovery() == null
                ? new MunicipalSourceProperties.Discovery()
                : municipalProperties.getDiscovery();
        this.metrics = metrics;
        this.clock = clock;
    }

    public List<FacilityView> nearby(double lat, double lng, int radiusMeters, int limit) {
        validate(lat, lng, radiusMeters, limit);
        if (!discovery.isDuplicatePresentationEnabled()) {
            return facilities.nearby(lat, lng, radiusMeters, limit).stream()
                    .filter(this::isDiscoverable)
                    .map(this::project)
                    .toList();
        }

        int fetchLimit = DiscoveryDuplicatePresentationPolicy.boundedFetchLimit(
                limit, discovery.getOverfetchFactor(), discovery.getOverfetchAbsoluteMax());
        List<MunicipalFacilityRepository.Facility> rows =
                facilities.nearby(lat, lng, radiusMeters, fetchLimit);

        List<DiscoveryDuplicatePresentationPolicy.Candidate> candidates = new ArrayList<>();
        for (MunicipalFacilityRepository.Facility facility : rows) {
            if (!isDiscoverable(facility)) {
                continue;
            }
            FacilityView view = project(facility);
            DiscoveryDuplicatePresentationPolicy.Family family =
                    DiscoveryDuplicatePresentationPolicy.familyOf(
                            linkedKeys(facility), facility.primarySourceKey());
            candidates.add(new DiscoveryDuplicatePresentationPolicy.Candidate(
                    facility.id(),
                    family,
                    facility.displayName(),
                    facility.operatorName(),
                    facility.addressText(),
                    facility.latitude(),
                    facility.longitude(),
                    facility.capacityTotal(),
                    facility.facilityType(),
                    facility.accessClassification() == null
                            ? MunicipalAccessClassification.UNKNOWN
                            : facility.accessClassification(),
                    view.freshness(),
                    view));
        }

        var policy = new DiscoveryDuplicatePresentationPolicy(
                discovery.getDuplicateRadiusMeters(), supportedPairs());
        DiscoveryDuplicatePresentationPolicy.ApplyResult<FacilityView> applied =
                policy.apply(candidates, limit);
        if (metrics != null) {
            metrics.record(applied);
        }
        return applied.kept();
    }

    public Optional<FacilityView> findById(UUID id) {
        return facilities.findById(id).filter(this::isDiscoverable).map(this::project);
    }

    private Set<String> supportedPairs() {
        Set<String> pairs = new HashSet<>();
        if (discovery.getSupportedPairs() != null) {
            for (String pair : discovery.getSupportedPairs()) {
                if (pair != null && !pair.isBlank()) {
                    pairs.add(pair.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (pairs.isEmpty()) {
            return DiscoveryDuplicatePresentationPolicy.supportedPairsDefault();
        }
        return pairs;
    }

    private FacilityView project(MunicipalFacilityRepository.Facility facility) {
        Set<String> linked = linkedKeys(facility);
        var snapshot = snapshots.latestForFacility(facility.id());
        MunicipalOccupancyFreshness freshness = MunicipalOccupancyFreshness.UNAVAILABLE;
        Integer available = null;
        Instant lastUpdated = null;
        Integer capacity = facility.capacityTotal();

        boolean mayLive = publicationPolicy.mayContributeLiveOccupancy(linked);
        if (mayLive && snapshot.isPresent()) {
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
        } else if (!mayLive) {
            available = null;
            freshness = MunicipalOccupancyFreshness.UNAVAILABLE;
        }

        // Suppress capacity that could only come from unpublished IZELMAN inventory.
        if (!mayLive
                && linked.stream().anyMatch(MunicipalSourceIdentity::isIzelmanFacilityInventory)
                && !publicationPolicy.mayContributeIzelmanInventoryFields(linked)
                && !publicationPolicy.mayContributeOsmFields(linked)) {
            capacity = null;
        }

        DisplayProvenance display = displayProvenance(facility, linked);
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
                display.attribution(),
                display.sourceLabel(),
                lastUpdated);
    }

    private DisplayProvenance displayProvenance(
            MunicipalFacilityRepository.Facility facility, Set<String> linked) {
        Set<String> publishable = publicationPolicy.publishableSourceKeys(linked);
        boolean izelmanInventoryPublishable = publicationPolicy.mayContributeIzelmanInventoryFields(linked);
        if (publishable.contains(MunicipalSourceIdentity.IZUM)) {
            // Keep IZUM attribution even when it mentions IZELMAN in a disclaimer.
            return new DisplayProvenance(IZUM_SOURCE_LABEL, IZUM_ATTRIBUTION);
        }
        if (publishable.contains(MunicipalSourceIdentity.OSM) && !izelmanInventoryPublishable) {
            return new DisplayProvenance(OSM_SOURCE_LABEL, OSM_ATTRIBUTION);
        }
        if (publishable.contains(MunicipalSourceIdentity.OSM)) {
            return new DisplayProvenance(
                    facility.sourceLabel() != null ? facility.sourceLabel() : OSM_SOURCE_LABEL,
                    facility.attribution() != null ? facility.attribution() : OSM_ATTRIBUTION);
        }
        if (izelmanInventoryPublishable) {
            return new DisplayProvenance(facility.sourceLabel(), facility.attribution());
        }
        return new DisplayProvenance(facility.sourceLabel(), facility.attribution());
    }

    private boolean isDiscoverable(MunicipalFacilityRepository.Facility facility) {
        return publicationPolicy.isFacilityPublishable(linkedKeys(facility), facility.primarySourceKey());
    }

    private static Set<String> linkedKeys(MunicipalFacilityRepository.Facility facility) {
        Set<String> linked = MunicipalSourceIdentity.normalizeKeys(facility.linkedSourceKeys());
        if (!linked.isEmpty()) {
            return linked;
        }
        if (facility.primarySourceKey() != null && !facility.primarySourceKey().isBlank()) {
            return Set.of(facility.primarySourceKey().trim());
        }
        return Set.of();
    }

    private static void validate(double lat, double lng, int radius, int limit) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90
                || !Double.isFinite(lng) || lng < -180 || lng > 180
                || radius <= 0 || radius > 50000 || limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("invalid nearby query");
        }
    }

    private record DisplayProvenance(String sourceLabel, String attribution) {}
}
