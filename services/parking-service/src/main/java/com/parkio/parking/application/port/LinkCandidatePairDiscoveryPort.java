package com.parkio.parking.application.port;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.registry.RegistrySourceFamilyPair;
import java.util.List;
import java.util.UUID;

public interface LinkCandidatePairDiscoveryPort {
    record SourceRecord(
            UUID facilityId,
            UUID linkId,
            String sourceKey,
            String externalId,
            String rawRecordHash,
            String name,
            String operator,
            MunicipalFacilityType type,
            MunicipalAccessClassification access,
            Integer capacity,
            double latitude,
            double longitude,
            String address,
            String sourceMetadataJson,
            boolean facilityActive,
            boolean linkActive,
            String lifecycleState) {}

    record DiscoveredPair(SourceRecord left, SourceRecord right, double distanceMeters) {}

    record DiscoveryResult(List<DiscoveredPair> pairs, int leftRecordsConsidered) {}

    DiscoveryResult discover(
            RegistrySourceFamilyPair pair,
            RegistrySourceFamilyPair.Family leftFamily,
            double maxDistanceMeters,
            int leftRecordLimit,
            int pairLimit,
            List<UUID> leftFacilityIds,
            List<String> leftExternalIds);

    boolean alreadyLinked(DiscoveredPair pair);
}
