package com.parkio.parking.externalsource.osm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Contract tests for DATA-WP-08 extraction scripts (no live osmium / Overpass). */
class IzmirOsmPolygonExtractScriptTest {
    @Test
    void extractScriptUsesPolygonNotBboxAndPreservesPreviousOutputs() throws Exception {
        String text = Files.readString(resolveScript("extract-izmir-osm-polygon.sh"), StandardCharsets.UTF_8);
        assertThat(text).contains("extract");
        assertThat(text).contains("-p /boundary/izmir-admin-boundary.poly");
        assertThat(text).doesNotContain("-b 26.20,37.85,28.45,39.05");
        assertThat(text).contains("izmir-admin-izbb-2024-10-18-v1");
        assertThat(text).contains("mktemp");
        assertThat(text).contains(".prev");
        assertThat(text).contains("izmir-bbox-v1.osm.pbf");
        assertThat(text).contains("refusing promote");
        assertThat(text).contains("getid -r");
        assertThat(text).doesNotContain("overpass-api");
        assertThat(text).doesNotContain("https://overpass");
    }

    @Test
    void validateScriptEnforcesChecksumsAndClipHeader() throws Exception {
        String text = Files.readString(resolveScript("validate-boundary-asset.sh"), StandardCharsets.UTF_8);
        assertThat(text).contains("6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89");
        assertThat(text).contains("ddd5664064a6bad22920d64a9f83c5c11c3ba85e4fb0e55a17bd3a26c31d2b61");
        assertThat(text).contains("5b20558b28e93c1fb7f2bcda2e36142b186846e63a7151285dae76bb19f5d7b1");
        assertThat(text).contains("izmir-admin-izbb-2024-10-18-v1");
    }

    private static Path resolveScript(String name) {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path[] candidates = new Path[] {
            cwd.resolve("scripts/data-wp-08").resolve(name),
            cwd.resolve("../scripts/data-wp-08").resolve(name).normalize(),
            cwd.resolve("../../scripts/data-wp-08").resolve(name).normalize()
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("script not found: " + name + " from " + cwd);
    }
}
