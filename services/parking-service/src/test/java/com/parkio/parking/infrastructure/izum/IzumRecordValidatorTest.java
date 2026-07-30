package com.parkio.parking.infrastructure.izum;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class IzumRecordValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final IzumRecordValidator validator = new IzumRecordValidator();

    @Test void acceptsFixtureRecords() throws IOException {
        List<IzumParkingRecordDto> records = mapper.readerForListOf(IzumParkingRecordDto.class)
                .readValue(getClass().getResourceAsStream("/fixtures/municipal/izum/otoparklar-sample.json"));
        assertThat(records).hasSizeGreaterThan(1).allMatch(record -> validator.validate(record).valid());
    }

    @Test void rejectsMissingIdentityBadCoordinatesAndNegativeOccupancy() {
        var record = new IzumParkingRecordDto(null, "bad", null, "OnStreet", null, 100D, 200D,
                new IzumParkingRecordDto.Occupancy(new IzumParkingRecordDto.Total(-1, 2), null),
                null, null, null, null, null, null, null, null, null, null);
        assertThat(validator.validate(record).errors())
                .contains("ufid_missing", "latitude_invalid", "longitude_invalid", "free_negative");
    }
}
