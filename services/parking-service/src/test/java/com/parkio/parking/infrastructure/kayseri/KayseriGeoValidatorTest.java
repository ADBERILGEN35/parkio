package com.parkio.parking.infrastructure.kayseri;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KayseriGeoValidatorTest {
    private final KayseriGeoValidator validator = new KayseriGeoValidator();

    @Test
    void acceptsObservedCityCoreAndRejectsIstanbulAndNonFinite() {
        assertThat(validator.isValid(38.715748, 35.491699)).isTrue();
        assertThat(validator.isValid(41.0, 29.0)).isFalse();
        assertThat(validator.isValid(Double.NaN, 35.5)).isFalse();
        assertThat(validator.isValid(38.7, Double.POSITIVE_INFINITY)).isFalse();
    }
}
