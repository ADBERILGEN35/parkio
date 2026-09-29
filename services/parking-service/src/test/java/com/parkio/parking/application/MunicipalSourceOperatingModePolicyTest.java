package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSourceOperatingMode;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import org.junit.jupiter.api.Test;

class MunicipalSourceOperatingModePolicyTest {

    @Test
    void defaultsMapIzumScheduledAndOsmOperatorImported() {
        MunicipalSourceProperties properties = new MunicipalSourceProperties();
        assertThat(properties.getOps().isSourceModeSlaEnabled()).isFalse();
        assertThat(MunicipalSourceOperatingModePolicy.resolve(MunicipalSourceIdentity.IZUM, properties))
                .isEqualTo(MunicipalSourceOperatingMode.SCHEDULED);
        assertThat(MunicipalSourceOperatingModePolicy.resolve(MunicipalSourceIdentity.OSM, properties))
                .isEqualTo(MunicipalSourceOperatingMode.OPERATOR_IMPORTED);
        assertThat(MunicipalSourceOperatingModePolicy.resolve(IzelmanSourceKeys.OPEN, properties))
                .isEqualTo(MunicipalSourceOperatingMode.OPERATOR_IMPORTED);
    }

    @Test
    void unknownSourceFailsClosedToScheduled() {
        MunicipalSourceProperties properties = new MunicipalSourceProperties();
        assertThat(MunicipalSourceOperatingModePolicy.resolve("unknown-source-key", properties))
                .isEqualTo(MunicipalSourceOperatingMode.SCHEDULED);
    }

    @Test
    void unknownModeStringRejected() {
        assertThatThrownBy(() -> MunicipalSourceOperatingMode.parse("HYBRID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
    }

    @Test
    void ageThresholdsApplyOnlyForScheduledWhenFlagEnabled() {
        assertThat(MunicipalSourceOperatingModePolicy.applySecondsSinceSuccessThresholds(
                        MunicipalSourceOperatingMode.OPERATOR_IMPORTED, true))
                .isFalse();
        assertThat(MunicipalSourceOperatingModePolicy.applySecondsSinceSuccessThresholds(
                        MunicipalSourceOperatingMode.SCHEDULED, true))
                .isTrue();
        assertThat(MunicipalSourceOperatingModePolicy.applySecondsSinceSuccessThresholds(
                        MunicipalSourceOperatingMode.OPERATOR_IMPORTED, false))
                .isTrue();
    }
}
