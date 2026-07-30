package com.parkio.parking.application.port;

import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import java.time.Instant;
import java.util.UUID;

public interface MunicipalSourceLinkRepository {
    UUID upsert(UUID facilityId, UUID sourceId, NormalizedMunicipalFacility facility, Instant seenAt);
}
