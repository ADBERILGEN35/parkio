package com.parkio.parking.externalsource.osm;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import org.junit.jupiter.api.Test;

class ConflationPolicyTest {
    @Test void doesNotAutoMatchUnnamedNearbyByDistanceAlone() {
        var candidate = ConflationPolicy.evaluate(
                "node/1", null, null, MunicipalFacilityType.OFF_STREET, MunicipalAccessClassification.PUBLIC, 100,
                38.4187, 27.1283,
                "ufid-1", "Konak Otopark", "Izmir", MunicipalFacilityType.OFF_STREET,
                MunicipalAccessClassification.PUBLIC, 120, 38.41871, 27.12831);
        assertThat(ConflationPolicy.decide(candidate, true))
                .isIn(ConflationDecision.REVIEW_REQUIRED, ConflationDecision.NOT_MATCHED);
        assertThat(ConflationPolicy.decide(candidate, true)).isNotEqualTo(ConflationDecision.AUTO_MATCHED);
    }

    @Test void autoMatchesStrongNameAndCloseGeometry() {
        var candidate = ConflationPolicy.evaluate(
                "node/1", "Konak Test Otopark", "Izmir Belediyesi", MunicipalFacilityType.OFF_STREET,
                MunicipalAccessClassification.PUBLIC, 120, 38.4187, 27.1283,
                "ufid-1", "Konak Test Otopark", "Izmir Belediyesi", MunicipalFacilityType.OFF_STREET,
                MunicipalAccessClassification.PUBLIC, 120, 38.41871, 27.12831);
        assertThat(ConflationPolicy.decide(candidate, false)).isEqualTo(ConflationDecision.AUTO_MATCHED);
    }

    @Test void hardConflictOnExclusiveTypes() {
        var candidate = ConflationPolicy.evaluate(
                "node/1", "A", null, MunicipalFacilityType.ON_STREET, MunicipalAccessClassification.PUBLIC, null,
                38.4, 27.1,
                "ufid-1", "A", null, MunicipalFacilityType.OFF_STREET, MunicipalAccessClassification.PUBLIC, null,
                38.40001, 27.10001);
        assertThat(candidate.hardConflict()).isTrue();
        assertThat(ConflationPolicy.decide(candidate, false)).isEqualTo(ConflationDecision.REJECTED);
    }
}