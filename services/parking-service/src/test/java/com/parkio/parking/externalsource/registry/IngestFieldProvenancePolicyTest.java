package com.parkio.parking.externalsource.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestFieldProvenancePolicyTest {

    @Test
    void izumSelectsOnlySuppliedAllowListedFields() {
        NormalizedMunicipalFacility facility = new NormalizedMunicipalFacility(
                "ufid-1",
                "Operator",
                MunicipalFacilityType.OFF_STREET,
                "Name",
                "Street 1",
                38.4,
                27.1,
                100,
                MunicipalAccessClassification.PUBLIC,
                Map.of(),
                "hash");
        assertThat(IngestFieldProvenancePolicy.forIzumFacility(facility))
                .extracting(IngestFieldProvenancePolicy.SuppliedField::field)
                .containsExactly(
                        RegistryField.NAME,
                        RegistryField.COORDINATES,
                        RegistryField.ADDRESS,
                        RegistryField.OPERATOR,
                        RegistryField.FACILITY_TYPE,
                        RegistryField.STATIC_CAPACITY,
                        RegistryField.ATTRIBUTION);
    }

    @Test
    void izumSkipsNullAddressOperatorAndCapacity() {
        NormalizedMunicipalFacility facility = new NormalizedMunicipalFacility(
                "ufid-2",
                null,
                MunicipalFacilityType.UNKNOWN,
                "Name",
                null,
                38.4,
                27.1,
                null,
                MunicipalAccessClassification.PUBLIC,
                Map.of(),
                "hash");
        assertThat(IngestFieldProvenancePolicy.forIzumFacility(facility))
                .extracting(IngestFieldProvenancePolicy.SuppliedField::field)
                .containsExactly(
                        RegistryField.NAME,
                        RegistryField.COORDINATES,
                        RegistryField.FACILITY_TYPE,
                        RegistryField.ATTRIBUTION)
                .doesNotContain(RegistryField.ADDRESS, RegistryField.OPERATOR, RegistryField.STATIC_CAPACITY);
    }

    @Test
    void osmSkipsSyntheticNameAndNeverClaimsAddress() {
        NormalizedMunicipalFacility facility = new NormalizedMunicipalFacility(
                "way/1",
                null,
                MunicipalFacilityType.OFF_STREET,
                "OSM parking way/1",
                null,
                38.4,
                27.1,
                12,
                MunicipalAccessClassification.PUBLIC,
                Map.of(),
                "hash");
        assertThat(IngestFieldProvenancePolicy.forOsmFacility(facility, false))
                .extracting(IngestFieldProvenancePolicy.SuppliedField::field)
                .containsExactly(
                        RegistryField.COORDINATES,
                        RegistryField.FACILITY_TYPE,
                        RegistryField.STATIC_CAPACITY,
                        RegistryField.ATTRIBUTION)
                .doesNotContain(RegistryField.NAME, RegistryField.ADDRESS);
    }

    @Test
    void osmClaimsNameWhenTagPresent() {
        NormalizedMunicipalFacility facility = new NormalizedMunicipalFacility(
                "way/2",
                "Op",
                MunicipalFacilityType.OFF_STREET,
                "Konak",
                null,
                38.4,
                27.1,
                null,
                MunicipalAccessClassification.PUBLIC,
                Map.of(),
                "hash");
        assertThat(IngestFieldProvenancePolicy.forOsmFacility(facility, true))
                .extracting(IngestFieldProvenancePolicy.SuppliedField::field)
                .contains(RegistryField.NAME, RegistryField.OPERATOR)
                .doesNotContain(RegistryField.ADDRESS, RegistryField.STATIC_CAPACITY);
    }
}
