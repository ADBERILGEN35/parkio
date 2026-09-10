package com.parkio.parking.infrastructure.anpark;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.validation.MunicipalRecordValidation;
import org.junit.jupiter.api.Test;

class AnparkNormalizerAndValidatorTest {
    private final AnparkRecordValidator validator = new AnparkRecordValidator();

    @Test
    void capacityZeroMapsToUnknownNull() {
        assertThat(AnparkNormalizer.normalizeCapacity(0)).isNull();
        assertThat(AnparkNormalizer.normalizeCapacity(-3)).isNull();
        assertThat(AnparkNormalizer.normalizeCapacity(null)).isNull();
        assertThat(AnparkNormalizer.normalizeCapacity(40)).isEqualTo(40);
    }

    @Test
    void typeMapping() {
        assertThat(AnparkNormalizer.facilityType("yolustu")).isEqualTo(MunicipalFacilityType.ON_STREET);
        assertThat(AnparkNormalizer.facilityType("acik")).isEqualTo(MunicipalFacilityType.OFF_STREET);
        assertThat(AnparkNormalizer.facilityType("kapali")).isEqualTo(MunicipalFacilityType.OFF_STREET);
        assertThat(AnparkNormalizer.facilityType("rekreasyon")).isEqualTo(MunicipalFacilityType.OFF_STREET);
        assertThat(AnparkNormalizer.facilityType("weird")).isEqualTo(MunicipalFacilityType.UNKNOWN);
        assertThat(AnparkNormalizer.facilityType(null)).isEqualTo(MunicipalFacilityType.UNKNOWN);
    }

    @Test
    void validatorRejectsNegativeCapacityButAllowsZero() {
        AnparkParkingRecordDto zero = new AnparkParkingRecordDto(
                "1", "A", "acik", "Çankaya", 39.9, 32.8, 0, "08-18", "addr", true);
        assertThat(validator.validate(zero).valid()).isTrue();

        AnparkParkingRecordDto neg = new AnparkParkingRecordDto(
                "1", "A", "acik", "Çankaya", 39.9, 32.8, -1, "08-18", "addr", true);
        MunicipalRecordValidation result = validator.validate(neg);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("capacity_invalid");
    }
}
