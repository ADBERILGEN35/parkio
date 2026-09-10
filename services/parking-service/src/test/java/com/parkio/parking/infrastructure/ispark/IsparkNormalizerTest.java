package com.parkio.parking.infrastructure.ispark;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalTimestampProvenance;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IsparkNormalizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final IsparkNormalizer normalizer = new IsparkNormalizer(mapper);

    @Test
    void mapsListFixtureToFacilityAndFetchProvenanceOccupancy() throws IOException {
        List<IsparkParkingRecordDto> records = mapper.readerForListOf(IsparkParkingRecordDto.class)
                .readValue(getClass().getResourceAsStream("/fixtures/municipal/ispark/park-sample.json"));
        Instant fetchedAt = Instant.parse("2026-08-07T09:00:00Z");

        var facility = normalizer.facility(records.get(0));
        var occupancy = normalizer.occupancy(records.get(0), fetchedAt);

        assertThat(facility.externalId()).isEqualTo("1001");
        assertThat(facility.operatorName()).isEqualTo("İSPARK");
        assertThat(facility.facilityType()).isEqualTo(MunicipalFacilityType.OFF_STREET);
        assertThat(facility.addressText()).isEqualTo("KADIKÖY");
        assertThat(facility.capacityTotal()).isEqualTo(120);
        assertThat(occupancy.availableSpaces()).isEqualTo(45);
        assertThat(occupancy.occupiedSpaces()).isEqualTo(75);
        assertThat(occupancy.timestampProvenance()).isEqualTo(MunicipalTimestampProvenance.FETCH);
        assertThat(occupancy.sourceObservedAt()).isNull();
    }

    @Test
    void mapsKnownParkTypes() {
        assertThat(IsparkNormalizer.facilityType("AÇIK OTOPARK"))
                .isEqualTo(MunicipalFacilityType.OFF_STREET);
        assertThat(IsparkNormalizer.facilityType("KAPALI OTOPARK"))
                .isEqualTo(MunicipalFacilityType.OFF_STREET);
        assertThat(IsparkNormalizer.facilityType("YOL ÜSTÜ"))
                .isEqualTo(MunicipalFacilityType.ON_STREET);
        assertThat(IsparkNormalizer.facilityType("UNKNOWN_THING"))
                .isEqualTo(MunicipalFacilityType.UNKNOWN);
        assertThat(IsparkNormalizer.facilityType(null))
                .isEqualTo(MunicipalFacilityType.UNKNOWN);
    }

    @Test
    void preservesZeroAvailable() {
        IsparkParkingRecordDto record = new IsparkParkingRecordDto(
                9, "Zero Lot", 41.0, 29.0, 50, 0, "24 Saat", "KAPALI OTOPARK", 0, "BEŞİKTAŞ", 1);
        var occupancy = normalizer.occupancy(record, Instant.parse("2026-08-07T09:00:00Z"));
        assertThat(occupancy.availableSpaces()).isEqualTo(0);
        assertThat(occupancy.occupiedSpaces()).isEqualTo(50);
    }
}
