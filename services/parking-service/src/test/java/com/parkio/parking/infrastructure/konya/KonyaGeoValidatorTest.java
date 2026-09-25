package com.parkio.parking.infrastructure.konya;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KonyaGeoValidatorTest {
    private final KonyaGeoValidator validator = new KonyaGeoValidator();

    @Test
    void acceptsValidKonyaPointAndRejectsSuspiciousLatitude() {
        assertThat(validator.isValidCoordinate(37.8728907124379, 32.48686462640762)).isTrue();
        assertThat(validator.isValidCoordinate(39.8770403869553, 32.7478837966919)).isFalse();
        assertThat(validator.isValidCoordinate(Double.NaN, 32.4)).isFalse();
    }
}
