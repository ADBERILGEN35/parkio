package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MunicipalFacilityControllerRadiusTest {
    @Test
    void defaultsWhenNeitherParameterPresent() {
        assertThat(MunicipalFacilityController.resolveRadiusMeters(null, null)).isEqualTo(1000);
    }

    @Test
    void prefersRadiusMetersOverLegacyRadius() {
        assertThat(MunicipalFacilityController.resolveRadiusMeters(2500, 1000)).isEqualTo(2500);
    }

    @Test
    void acceptsLegacyRadiusWhenCanonicalAbsent() {
        assertThat(MunicipalFacilityController.resolveRadiusMeters(null, 4000)).isEqualTo(4000);
    }

    @Test
    void passesInvalidValuesThroughForServiceValidation() {
        assertThat(MunicipalFacilityController.resolveRadiusMeters(0, null)).isZero();
        assertThat(MunicipalFacilityController.resolveRadiusMeters(-5, null)).isEqualTo(-5);
        assertThat(MunicipalFacilityController.resolveRadiusMeters(60_000, null)).isEqualTo(60_000);
    }

    @Test
    void nearbyLimitDefaultsToDenseViewportCap() {
        assertThat(MunicipalFacilityController.resolveNearbyLimit(null)).isEqualTo(100);
        assertThat(MunicipalFacilityController.resolveNearbyLimit(0)).isEqualTo(100);
        assertThat(MunicipalFacilityController.resolveNearbyLimit(-1)).isEqualTo(100);
    }

    @Test
    void nearbyLimitClampsAboveMax() {
        assertThat(MunicipalFacilityController.resolveNearbyLimit(50)).isEqualTo(50);
        assertThat(MunicipalFacilityController.resolveNearbyLimit(100)).isEqualTo(100);
        assertThat(MunicipalFacilityController.resolveNearbyLimit(500)).isEqualTo(100);
    }
}
