package com.parkio.parking.externalsource.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RegistryPolicyTest {
    @Test
    void onlyIzumMaySupplyLiveOccupancy() {
        assertThat(CanonicalFieldPrecedencePolicy.mayCreateOccupancySnapshot("izmir-izum-otoparklar"))
                .isTrue();
        assertThat(CanonicalFieldPrecedencePolicy.mayCreateOccupancySnapshot("osm-geofabrik-turkey"))
                .isFalse();
        assertThat(CanonicalFieldPrecedencePolicy.mayCreateOccupancySnapshot("izelman-open-parking-facilities"))
                .isFalse();
    }

    @Test
    void sourceFamiliesAndRegistryPrecedenceAreStable() {
        assertThat(CanonicalFieldPrecedencePolicy.sourceFamily("izmir-izum-otoparklar")).isEqualTo("IZUM");
        assertThat(CanonicalFieldPrecedencePolicy.sourceFamily("osm-geofabrik-turkey")).isEqualTo("OSM");
        assertThat(CanonicalFieldPrecedencePolicy.sourceFamily("izelman-open-parking-facilities"))
                .isEqualTo("IZELMAN");
        assertThat(CanonicalFieldPrecedencePolicy.preferName("Municipal name", "OSM name"))
                .isEqualTo("Municipal name");
        assertThat(CanonicalFieldPrecedencePolicy.preferOperator("Official Op", "OSM Op"))
                .isEqualTo("Official Op");
        assertThat(CanonicalFieldPrecedencePolicy.preferStaticCapacity(
                        120, FieldProvenanceSelection.SourceAgeClass.CURRENT, 90))
                .isEqualTo(120);
        assertThat(CanonicalFieldPrecedencePolicy.preferStaticCapacity(
                        120, FieldProvenanceSelection.SourceAgeClass.HISTORICAL, 90))
                .isEqualTo(90);
        assertThat(CanonicalFieldPrecedencePolicy.preferAccess(
                        MunicipalAccessClassification.PUBLIC, MunicipalAccessClassification.RESTRICTED))
                .isEqualTo(MunicipalAccessClassification.RESTRICTED);
    }

    @Test
    void distanceAloneAndNameAloneNeverGenerateCandidates() {
        assertThat(LinkCandidatePolicy.evaluate(evidence(
                                10, 0, 0, false, false,
                                MunicipalFacilityType.UNKNOWN, MunicipalFacilityType.UNKNOWN))
                        .candidate())
                .isFalse();
        assertThat(LinkCandidatePolicy.evaluate(evidence(
                                500, 1, 0, false, false,
                                MunicipalFacilityType.UNKNOWN, MunicipalFacilityType.UNKNOWN))
                        .candidate())
                .isFalse();
    }

    @Test
    void multiSignalEvidenceGeneratesReviewCandidateButNeverAutoLinks() {
        LinkCandidateScore score = LinkCandidatePolicy.evaluate(evidence(
                10, 0.95, 0.9, true, true,
                MunicipalFacilityType.OFF_STREET, MunicipalFacilityType.OFF_STREET));
        assertThat(score.candidate()).isTrue();
        assertThat(score.mayAutoLink()).isFalse();
        assertThat(score.supportingSignals()).contains("distance", "name", "operator");
    }

    @Test
    void hardConflictsSurfaceForReviewAndBlockCandidateLinking() {
        LinkCandidateScore score = LinkCandidatePolicy.evaluate(evidence(
                5, 1, 1, true, true,
                MunicipalFacilityType.ON_STREET, MunicipalFacilityType.OFF_STREET));
        assertThat(score.hardConflicts()).contains("facility_type_exclusive");
        assertThat(score.candidate()).isFalse();
        assertThat(score.reviewRequired()).isTrue();
        assertThat(score.mayAutoLink()).isFalse();
    }

    @Test
    void materialCoordinateAndOperatorConflictsAreHardConflicts() {
        LinkCandidateScore farSameName = LinkCandidatePolicy.evaluate(evidence(
                200, 0.95, 0.2, false, false,
                MunicipalFacilityType.OFF_STREET, MunicipalFacilityType.OFF_STREET));
        assertThat(farSameName.hardConflicts()).contains("material_coordinate_conflict");
        assertThat(farSameName.candidate()).isFalse();

        LinkCandidateEvidence operatorClash = new LinkCandidateEvidence(
                "izmir-izum-otoparklar", "izum-1", "v1",
                "osm-geofabrik-turkey", "node-2", "v1",
                12, 0.95, 0.1,
                MunicipalFacilityType.OFF_STREET, MunicipalFacilityType.OFF_STREET,
                MunicipalAccessClassification.PUBLIC, MunicipalAccessClassification.PUBLIC,
                100, 100, true, true, true, false, false, false);
        assertThat(LinkCandidatePolicy.evaluate(operatorClash).hardConflicts())
                .contains("operator_contradiction");
    }

    @Test
    void tariffRequiresExplicitStrongCurrentEvidence() {
        assertThat(TariffAssignmentPolicy.mayAssign(new TariffAssignmentPolicy.Evidence(
                        true, Set.of("official_tariff_code"),
                        FieldProvenanceSelection.SourceAgeClass.CURRENT)))
                .isTrue();
        assertThat(TariffAssignmentPolicy.mayAssign(new TariffAssignmentPolicy.Evidence(
                        true,
                        Set.of("proximity", "district", "similar_name", "operator", "capacity"),
                        FieldProvenanceSelection.SourceAgeClass.CURRENT)))
                .isFalse();
        assertThat(TariffAssignmentPolicy.mayAssign(new TariffAssignmentPolicy.Evidence(
                        true, Set.of("official_tariff_code"),
                        FieldProvenanceSelection.SourceAgeClass.HISTORICAL)))
                .isFalse();
        assertThat(TariffAssignmentPolicy.mayAssign(new TariffAssignmentPolicy.Evidence(
                        true, Set.of("official_tariff_code"),
                        FieldProvenanceSelection.SourceAgeClass.AGING)))
                .isFalse();
        assertThat(TariffAssignmentPolicy.proximityOnlyIsSufficient()).isFalse();
    }

    @Test
    void sourceFailureAndPartialRunsDoNotDeactivateLinks() {
        assertThat(SourceLifecyclePolicy.decide(
                        SourceLifecyclePolicy.SourceEvent.FAILURE, false, false, false)
                .deactivateSourceLink())
                .isFalse();
        assertThat(SourceLifecyclePolicy.decide(
                        SourceLifecyclePolicy.SourceEvent.PARTIAL_SUCCESS, false, false, false)
                .deactivateSourceLink())
                .isFalse();
        assertThat(SourceLifecyclePolicy.staleAvailabilityMayRemainHistory()).isTrue();
        assertThat(SourceLifecyclePolicy.staleAvailabilityMayBeReportedLive()).isFalse();
    }

    private static LinkCandidateEvidence evidence(
            double distance,
            double name,
            double operator,
            boolean address,
            boolean district,
            MunicipalFacilityType typeA,
            MunicipalFacilityType typeB) {
        return new LinkCandidateEvidence(
                "izmir-izum-otoparklar", "izum-1", "v1",
                "osm-geofabrik-turkey", "node-2", "v1",
                distance, name, operator, typeA, typeB,
                MunicipalAccessClassification.PUBLIC, MunicipalAccessClassification.PUBLIC,
                null, null, address, district);
    }
}