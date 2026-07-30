package com.parkio.parking.externalsource;

import java.time.Instant;

public record NormalizedMunicipalOccupancy(
        String externalId,
        Instant sourceObservedAt,
        Instant fetchedAt,
        MunicipalTimestampProvenance timestampProvenance,
        Integer capacityTotal,
        Integer occupiedSpaces,
        Integer availableSpaces,
        MunicipalOccupancyFreshness occupancyStatus,
        String rawRecordHash) {}
