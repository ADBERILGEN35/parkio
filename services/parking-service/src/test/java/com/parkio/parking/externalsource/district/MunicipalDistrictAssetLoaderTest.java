package com.parkio.parking.externalsource.district;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.district.MunicipalDistrictAssetLoader.LoadedAsset;
import com.parkio.parking.externalsource.osm.IzmirBoundaryAssetValidator;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DATA-WP-18: district boundary asset load and validation.
 */
class MunicipalDistrictAssetLoaderTest {
    private static final String OFFICIAL_SHA =
            "6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89";

    private MunicipalDistrictAssetLoader loader;
    private byte[] miniatureBytes;
    private String miniatureSha;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        loader = new MunicipalDistrictAssetLoader(new ObjectMapper());
        miniatureBytes = readFixture("ilceler-official-miniature.geojson");
        miniatureSha = sha256(miniatureBytes);
    }

    @Test
    void acceptsMiniatureWithItsOwnChecksum() {
        LoadedAsset asset = loader.load(
                miniatureBytes, miniatureSha, MunicipalDistrictCoveragePolicy.DEFAULT_NAME_PROPERTY, 30);

        assertThat(asset.valid()).isTrue();
        assertThat(asset.sourceSha256()).isEqualTo(miniatureSha);
        assertThat(asset.districts()).hasSize(30);
        assertThat(asset.districts()).anyMatch(d -> "KONAK".equals(d.districtName()));
    }

    @Test
    void acceptsMiniatureFromTempFilePath() throws Exception {
        Path path = tempDir.resolve("districts.geojson");
        Files.write(path, miniatureBytes);

        LoadedAsset asset = loader.load(
                path, miniatureSha, MunicipalDistrictCoveragePolicy.DEFAULT_NAME_PROPERTY, 30);

        assertThat(asset.valid()).isTrue();
        assertThat(asset.districts()).hasSize(30);
    }

    @Test
    void rejectsWrongChecksumIncludingOfficialShaOnMiniature() {
        LoadedAsset wrongOfficial = loader.load(
                miniatureBytes, OFFICIAL_SHA, MunicipalDistrictCoveragePolicy.DEFAULT_NAME_PROPERTY, 30);
        LoadedAsset wrongOther = loader.load(
                miniatureBytes, "0".repeat(64), MunicipalDistrictCoveragePolicy.DEFAULT_NAME_PROPERTY, 30);

        assertThat(wrongOfficial.valid()).isFalse();
        assertThat(wrongOfficial.sourceSha256()).isEqualTo(miniatureSha);
        assertThat(wrongOfficial.districts()).isEmpty();
        assertThat(wrongOther.valid()).isFalse();
    }

    @Test
    void rejectsInvalidJson() throws Exception {
        byte[] bytes = "{not-json".getBytes(StandardCharsets.UTF_8);
        LoadedAsset asset = loader.load(bytes, sha256(bytes), "adi", 0);

        assertThat(asset.valid()).isFalse();
    }

    @Test
    void rejectsEmptyFeatureCollection() throws Exception {
        byte[] bytes = readFixture("empty-fc.geojson");
        LoadedAsset asset = loader.load(bytes, sha256(bytes), "adi", 0);

        assertThat(asset.valid()).isFalse();
    }

    @Test
    void rejectsWrongDistrictCount() {
        LoadedAsset asset = loader.load(miniatureBytes, miniatureSha, "adi", 29);

        assertThat(asset.valid()).isFalse();
        assertThat(asset.sourceSha256()).isEqualTo(miniatureSha);
    }

    @Test
    void miniatureShaDiffersFromOfficialProductionAsset() {
        assertThat(miniatureSha).isNotEqualToIgnoringCase(OFFICIAL_SHA);
        assertThat(miniatureSha)
                .isEqualTo(IzmirBoundaryAssetValidator.sha256(miniatureBytes));
    }

    private static byte[] readFixture(String name) throws Exception {
        try (InputStream in = MunicipalDistrictAssetLoaderTest.class.getResourceAsStream(
                "/fixtures/municipal/boundary/" + name)) {
            assertThat(in).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
