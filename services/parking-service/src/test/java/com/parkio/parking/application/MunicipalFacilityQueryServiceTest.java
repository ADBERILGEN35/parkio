package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MunicipalFacilityQueryServiceTest {
    @Test void staleOccupancyNeverExposesAvailableSpaces() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var facility = new MunicipalFacilityRepository.Facility(
                id, "Facility", "İZELMAN", MunicipalFacilityType.OFF_STREET, "", 38.4, 27.1,
                100, true, true, "İZUM", "CC BY 4.0 attribution", 60, 120);
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        100, 60, 40, Instant.parse("2026-07-30T05:00:00Z"), null, true)));
        var service = new MunicipalFacilityQueryService(facilities, snapshots,
                Clock.fixed(Instant.parse("2026-07-30T06:00:00Z"), ZoneOffset.UTC));

        var result = service.findById(id).orElseThrow();

        assertThat(result.freshness()).isEqualTo(MunicipalOccupancyFreshness.STALE);
        assertThat(result.availableSpaces()).isNull();
        assertThat(result.capacityTotal()).isEqualTo(100);
    }
}
