package com.parkio.parking.externalsource.district;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.district.MunicipalDistrictAssignmentPolicy.Assignment;
import com.parkio.parking.externalsource.district.MunicipalDistrictAssignmentPolicy.Classification;
import com.parkio.parking.externalsource.osm.IzmirBoundaryAssetValidator;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DATA-WP-18: deterministic point-in-polygon district assignment.
 */
class MunicipalDistrictAssignmentPolicyTest {
    private static final double KONAK_WEST = 27.0;
    private static final double KONAK_EAST = 27.01;
    private static final double KONAK_SOUTH = 38.75;
    private static final double KONAK_NORTH = 38.76;

    private MunicipalDistrictAssignmentPolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        byte[] bytes = readFixture("ilceler-official-miniature.geojson");
        String sha = sha256(bytes);
        var loaded = new MunicipalDistrictAssetLoader(new ObjectMapper())
                .load(bytes, sha, MunicipalDistrictCoveragePolicy.DEFAULT_NAME_PROPERTY, 30);
        assertThat(loaded.valid()).isTrue();
        policy = new MunicipalDistrictAssignmentPolicy(loaded.districts());
    }

    @Test
    void insideKonakIsAssignedToKonak() {
        Assignment assignment = policy.assign(27.005, 38.755);

        assertThat(assignment.classification()).isEqualTo(Classification.ASSIGNED);
        assertThat(assignment.districtName()).isEqualTo("KONAK");
        assertThat(assignment.foldedName()).isEqualTo("KONAK");
        assertThat(assignment.overlapAnomaly()).isFalse();
    }

    @Test
    void boundaryPointRemainsAssigned() {
        Assignment assignment = policy.assign(KONAK_WEST, 38.755);

        assertThat(assignment.classification()).isEqualTo(Classification.ASSIGNED);
        assertThat(assignment.districtName()).isEqualTo("KONAK");
        assertThat(assignment.overlapAnomaly()).isFalse();
    }

    @Test
    void outsideAllDistrictsIsUnassigned() {
        Assignment assignment = policy.assign(25.0, 35.0);

        assertThat(assignment.classification()).isEqualTo(Classification.UNASSIGNED);
        assertThat(assignment.districtName()).isNull();
        assertThat(assignment.foldedName()).isNull();
        assertThat(assignment.overlapAnomaly()).isFalse();
    }

    @Test
    void nullOrNonFiniteCoordinatesAreInvalid() {
        assertThat(policy.assign(null, 38.755).classification())
                .isEqualTo(Classification.INVALID_COORDINATES);
        assertThat(policy.assign(27.005, null).classification())
                .isEqualTo(Classification.INVALID_COORDINATES);
        assertThat(policy.assign(Double.NaN, 38.755).classification())
                .isEqualTo(Classification.INVALID_COORDINATES);
        assertThat(policy.assign(27.005, Double.POSITIVE_INFINITY).classification())
                .isEqualTo(Classification.INVALID_COORDINATES);
    }

    @Test
    void overlappingDistrictsPickDeterministicFoldedNameAndFlagOverlap() {
        double[][] ring = square(KONAK_WEST, KONAK_SOUTH, KONAK_EAST, KONAK_NORTH);
        var konak = new MunicipalDistrictGeometry(
                "KONAK", IzmirBoundaryAssetValidator.foldDistrictName("KONAK"),
                List.of(new MunicipalDistrictGeometry.RingSet(ring, List.of())));
        var kinik = new MunicipalDistrictGeometry(
                "KINIK", IzmirBoundaryAssetValidator.foldDistrictName("KINIK"),
                List.of(new MunicipalDistrictGeometry.RingSet(ring, List.of())));
        var overlapPolicy = new MunicipalDistrictAssignmentPolicy(List.of(konak, kinik));

        Assignment assignment = overlapPolicy.assign(27.005, 38.755);

        assertThat(assignment.classification()).isEqualTo(Classification.ASSIGNED);
        assertThat(assignment.districtName()).isEqualTo("KINIK");
        assertThat(assignment.foldedName()).isEqualTo("KINIK");
        assertThat(assignment.overlapAnomaly()).isTrue();
    }

    @Test
    void oneFacilityReceivesExactlyOneAssignment() {
        Assignment first = policy.assign(27.005, 38.755);
        Assignment second = policy.assign(27.005, 38.755);

        assertThat(first).isEqualTo(second);
        assertThat(first.classification()).isEqualTo(Classification.ASSIGNED);
    }

    private static double[][] square(double west, double south, double east, double north) {
        return new double[][] {
                {west, south}, {east, south}, {east, north}, {west, north}, {west, south}
        };
    }

    private static byte[] readFixture(String name) throws Exception {
        try (InputStream in = MunicipalDistrictAssignmentPolicyTest.class.getResourceAsStream(
                "/fixtures/municipal/boundary/" + name)) {
            assertThat(in).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
