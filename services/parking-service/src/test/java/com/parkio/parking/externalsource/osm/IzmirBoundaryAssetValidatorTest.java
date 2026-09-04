package com.parkio.parking.externalsource.osm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IzmirBoundaryAssetValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final IzmirBoundaryAssetValidator validator = new IzmirBoundaryAssetValidator(mapper);

    @AfterEach
    void resetClip() {
        IzmirClip.resetToAdminEnvelope();
    }

    @Test
    void acceptsOfficialMiniatureMetadata() throws Exception {
        byte[] bytes = read("ilceler-official-miniature.geojson");
        var result = validator.validate(bytes, null);
        assertThat(result.accepted()).isTrue();
        assertThat(result.details().get("featureCount")).isEqualTo(30);
        assertThat(result.details().get("districtNameField")).isEqualTo("adi");
        assertThat(result.districtNames()).hasSize(30);
    }

    @Test
    void rejectsInvalidJson() {
        var result = validator.validate("{not-json".getBytes(StandardCharsets.UTF_8), null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("invalid_json");
    }

    @Test
    void rejectsEmptyFeatureCollection() throws Exception {
        var result = validator.validate(read("empty-fc.geojson"), null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("empty_feature_collection");
    }

    @Test
    void rejectsNonPolygonGeometry() throws Exception {
        var result = validator.validate(read("non-polygon.geojson"), null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.errors().stream().anyMatch(e -> e.startsWith("non_polygon_geometry:"))).isTrue();
    }

    @Test
    void rejectsMissingDistrictName() throws Exception {
        var result = validator.validate(read("missing-name.geojson"), null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("missing_district_name"));
    }

    @Test
    void detectsDuplicateDistrict() throws Exception {
        var result = validator.validate(read("duplicate-district.geojson"), null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.errors().stream().anyMatch(e -> e.startsWith("duplicate_district:"))).isTrue();
    }

    @Test
    void detectsMissingExpectedDistrict() throws Exception {
        var result = validator.validate(read("missing-district.geojson"), null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.errors().stream().anyMatch(e -> e.startsWith("missing_expected_districts:"))).isTrue();
    }

    @Test
    void detectsForeignDistrict() throws Exception {
        var result = validator.validate(read("foreign-district.geojson"), null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.errors().stream().anyMatch(e -> e.startsWith("foreign_or_unexpected_district:"))).isTrue();
    }

    @Test
    void rejectsImplausibleBounds() throws Exception {
        var result = validator.validate(read("wrong-bounds.geojson"), null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.errors().stream().anyMatch(e -> e.startsWith("implausible_crs_or_bounds:"))).isTrue();
    }

    @Test
    void reportsIslandRingEncodedAsHoleAsWarning() throws Exception {
        var result = validator.validate(read("island-as-hole.geojson"), null);
        // incomplete district set -> rejected, but warning recorded
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.details().get("warnings");
        assertThat(warnings).isNotNull();
        assertThat(warnings.stream().anyMatch(w -> w.startsWith("island_ring_encoded_as_hole:"))).isTrue();
    }

    @Test
    void checksumMismatchFails() throws Exception {
        byte[] bytes = read("ilceler-official-miniature.geojson");
        var result = validator.validate(bytes, "0".repeat(64));
        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("checksum_mismatch");
    }

    @Test
    void derivedMiniatureIsDeterministicMultiPolygon() throws Exception {
        byte[] a = read("derived-admin-miniature.geojson");
        byte[] b = read("derived-admin-miniature.geojson");
        assertThat(IzmirBoundaryAssetValidator.sha256(a))
                .isEqualTo(IzmirBoundaryAssetValidator.sha256(b));
        JsonNode root = mapper.readTree(a);
        assertThat(root.path("features").path(0).path("geometry").path("type").asText())
                .isEqualTo("MultiPolygon");
        assertThat(root.path("features").path(0).path("geometry").path("coordinates").size())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void polygonMembershipIncludesIslandsAndExcludesExternal() throws Exception {
        JsonNode root = mapper.readTree(read("derived-admin-miniature.geojson"));
        var rings = IzmirClip.exteriorRingsFromCoordinates(root.path("features").path(0).path("geometry"));
        IzmirClip.configure(IzmirClip.Membership.polygon(rings));
        assertThat(IzmirClip.contains(38.2, 26.5)).isTrue();
        assertThat(IzmirClip.contains(38.52, 26.72)).isTrue(); // island component
        assertThat(IzmirClip.contains(41.0, 28.0)).isFalse();
        assertThat(IzmirClip.currentMembership().mode()).isEqualTo("polygon");
    }

    @Test
    void legacyBboxRollbackMembership() {
        IzmirClip.resetToLegacyBbox();
        assertThat(IzmirClip.contains(38.4, 27.1)).isTrue();
        assertThat(IzmirClip.contains(39.2, 27.1)).isFalse(); // north of legacy bbox
        assertThat(IzmirClip.currentMembership().mode()).isEqualTo("legacy-bbox");
    }

    private static byte[] read(String name) throws Exception {
        try (InputStream in = IzmirBoundaryAssetValidatorTest.class.getResourceAsStream(
                "/fixtures/municipal/boundary/" + name)) {
            assertThat(in).isNotNull();
            return in.readAllBytes();
        }
    }
}
