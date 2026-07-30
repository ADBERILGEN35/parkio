package com.parkio.parking.infrastructure.izum;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    }
}
