package com.parkio.parking.infrastructure.ispark;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IsparkRecordValidatorTest {
    private final IsparkRecordValidator validator = new IsparkRecordValidator();

    @Test
    void acceptsValidRecord() {
        assertThat(validator.validate(valid()).valid()).isTrue();
    }

    @Test
    void rejectsMissingName() {
        IsparkParkingRecordDto record = new IsparkParkingRecordDto(
                1, "  ", 41.0, 29.0, 10, 2, "24 Saat", "AÇIK OTOPARK", 0, "KADIKÖY", 1);
        assertThat(validator.validate(record).errors()).contains("name_missing");
    }

    @Test
    void rejectsMalformedCoordinates() {
        IsparkParkingRecordDto record = new IsparkParkingRecordDto(
                1, "Lot", 10.0, 29.0, 10, 2, "24 Saat", "AÇIK OTOPARK", 0, "KADIKÖY", 1);
        assertThat(validator.validate(record).errors()).contains("latitude_invalid");
    }

    @Test
    void rejectsEmptyExceedingCapacity() {
        IsparkParkingRecordDto record = new IsparkParkingRecordDto(
                1, "Lot", 41.0, 29.0, 10, 11, "24 Saat", "AÇIK OTOPARK", 0, "KADIKÖY", 1);
        assertThat(validator.validate(record).errors()).contains("empty_exceeds_capacity");
    }

    @Test
    void rejectsMissingParkId() {
        IsparkParkingRecordDto record = new IsparkParkingRecordDto(
                null, "Lot", 41.0, 29.0, 10, 2, "24 Saat", "AÇIK OTOPARK", 0, "KADIKÖY", 1);
        assertThat(validator.validate(record).errors()).contains("park_id_missing");
    }

    private static IsparkParkingRecordDto valid() {
        return new IsparkParkingRecordDto(
                1001, "Kadıköy Açık Otopark", 40.9901, 29.0292, 120, 45,
                "08:00-22:00", "AÇIK OTOPARK", 15, "KADIKÖY", 1);
    }
}
