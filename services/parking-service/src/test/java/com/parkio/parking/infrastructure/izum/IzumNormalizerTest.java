package com.parkio.parking.infrastructure.izum;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalTimestampProvenance;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IzumNormalizerTest {
    @Test void derivesCapacityAndFetchProvenanceFromFixture() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<IzumParkingRecordDto> records = mapper.readerForListOf(IzumParkingRecordDto.class)
                .readValue(getClass().getResourceAsStream("/fixtures/municipal/izum/otoparklar-sample.json"));
        IzumNormalizer normalizer = new IzumNormalizer(mapper);
        Instant fetchedAt = Instant.parse("2026-07-30T06:00:00Z");

        var facility = normalizer.facility(records.get(0));
        var occupancy = normalizer.occupancy(records.get(0), fetchedAt);

        assertThat(facility.capacityTotal()).isEqualTo(59);
        assertThat(occupancy.availableSpaces()).isEqualTo(1);
        assertThat(occupancy.occupiedSpaces()).isEqualTo(58);
        assertThat(occupancy.timestampProvenance()).isEqualTo(MunicipalTimestampProvenance.FETCH);
        assertThat(occupancy.sourceObservedAt()).isNull();
        assertThat(occupancy.occupancyStatus()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
    }

    // CL-F22 (d): a closed car park, or one without a free-space count, has no occupancy to publish.
    @Test void aClosedCarParkIsStoredUnavailableWithItsCounts() {
        var occupancy = new IzumNormalizer(new ObjectMapper()).occupancy(record(" CLOSED ", 4, 6), FETCHED_AT);

        assertThat(occupancy.occupancyStatus()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(occupancy.availableSpaces()).isEqualTo(4);
        assertThat(occupancy.capacityTotal()).isEqualTo(10);
    }

    @Test void aMissingFreeCountIsStoredUnavailable() {
        IzumNormalizer normalizer = new IzumNormalizer(new ObjectMapper());

        assertThat(normalizer.occupancy(record("Opened", null, 6), FETCHED_AT).occupancyStatus())
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(normalizer.occupancy(record("Opened", null, null), FETCHED_AT).occupancyStatus())
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    @Test void openedAndUnknownStatusesWithAFreeCountStayLive() {
        IzumNormalizer normalizer = new IzumNormalizer(new ObjectMapper());

        for (String status : new String[] {"Opened", "opened", null, "", "Maintenance"}) {
            assertThat(normalizer.occupancy(record(status, 4, 6), FETCHED_AT).occupancyStatus())
                    .as("status %s", status)
                    .isEqualTo(MunicipalOccupancyFreshness.LIVE);
        }
        // Only the free count matters for publishing: a missing occupied count keeps it live.
        assertThat(normalizer.occupancy(record("Opened", 4, null), FETCHED_AT).occupancyStatus())
                .isEqualTo(MunicipalOccupancyFreshness.LIVE);
    }

    private static final Instant FETCHED_AT = Instant.parse("2026-10-04T08:00:00Z");

    private static IzumParkingRecordDto record(String status, Integer free, Integer occupied) {
        var total = new IzumParkingRecordDto.Total(free, occupied);
        return new IzumParkingRecordDto("ufid-1", "Synthetic Car Park", "IZUM", "OffStreet", status, 38.42, 27.14,
                new IzumParkingRecordDto.Occupancy(total, null), null, true, false, "Synthetic Street 1",
                null, null, null, null, null, null);
    }
}
