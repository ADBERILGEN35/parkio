package com.parkio.parking.application.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.MunicipalDistrictFacilityProjection;
import com.parkio.parking.application.port.MunicipalQualityReportQueryPort;
import com.parkio.parking.externalsource.district.MunicipalDistrictAssetLoader;
import com.parkio.parking.externalsource.district.MunicipalDistrictCoveragePolicy;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.metrics.MunicipalDistrictCoverageMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DATA-WP-18: district coverage assembly, caching and bounded unavailable paths.
 */
class MunicipalDistrictCoverageAssemblerTest {
    private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");
    private static final long AGING = 300L;
    private static final long STALE = 900L;

    @TempDir
    Path tempDir;

    private MunicipalSourceProperties properties;
    private MunicipalQualityReportQueryPort queries;
    private MunicipalDistrictCoverageAssembler assembler;
    private byte[] miniatureBytes;
    private String miniatureSha;

    @BeforeEach
    void setUp() throws Exception {
        properties = new MunicipalSourceProperties();
        queries = mock(MunicipalQualityReportQueryPort.class);
        var registry = new SimpleMeterRegistry();
        var metrics = new MunicipalDistrictCoverageMetrics(registry);
        var loader = new MunicipalDistrictAssetLoader(new ObjectMapper());
        assembler = new MunicipalDistrictCoverageAssembler(
                properties, queries, loader, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

        miniatureBytes = readFixture("ilceler-official-miniature.geojson");
        miniatureSha = sha256(miniatureBytes);
    }

    @Test
    void defaultDisabledReturnsDisabledSectionWithoutQuerying() {
        properties.getOps().setDistrictCoverageEnabled(false);

        DistrictCoverageSection section = assembler.assemble(NOW, AGING, STALE);

        assertThat(section.status()).isEqualTo(DistrictCoverageStatus.DISABLED);
        assertThat(section.unavailableReason()).isEqualTo(MunicipalDistrictCoverageReason.DISABLED);
        assertThat(section.districts()).isEmpty();
        verify(queries, times(0)).listActiveFacilityProjections(anyInt(), anyLong(), anyLong(), any());
    }

    @Test
    void availableSectionAssignsProjectionsIntoDistrictRows() throws Exception {
        enableWithMiniatureAsset();
        UUID facilityId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(queries.listActiveFacilityProjections(eq(10_000), eq(AGING), eq(STALE), eq(NOW)))
                .thenReturn(List.of(new MunicipalDistrictFacilityProjection(
                        facilityId,
                        38.755,
                        27.005,
                        true,
                        true,
                        true,
                        true,
                        false,
                        true)));

        DistrictCoverageSection section = assembler.assemble(NOW, AGING, STALE);

        assertThat(section.status()).isEqualTo(DistrictCoverageStatus.AVAILABLE);
        assertThat(section.unavailableReason()).isNull();
        assertThat(section.policyVersion()).isEqualTo(MunicipalDistrictCoveragePolicy.POLICY_VERSION);
        assertThat(section.assetVersion()).isEqualTo(MunicipalDistrictCoveragePolicy.ASSET_VERSION);
        assertThat(section.districtCount()).isEqualTo(30);
        assertThat(section.activeFacilityCountConsidered()).isEqualTo(1);
        assertThat(section.assignedFacilityCount()).isEqualTo(1);
        assertThat(section.unassignedFacilityCount()).isZero();
        assertThat(section.invalidCoordinateCount()).isZero();
        assertThat(section.overlapAnomalyCount()).isZero();

        DistrictCoverageEntry konak = section.districts().stream()
                .filter(row -> "KONAK".equals(row.districtName()))
                .findFirst()
                .orElseThrow();
        assertThat(konak.totalActiveFacilities()).isEqualTo(1);
        assertThat(konak.activeOsmFacilities()).isEqualTo(1);
        assertThat(konak.activeIzumFacilities()).isEqualTo(1);
        assertThat(konak.availabilityExposedIzumFacilities()).isEqualTo(1);
        assertThat(konak.realNameOsmFacilities()).isEqualTo(1);
        assertThat(konak.provenanceCoveredFacilities()).isEqualTo(1);
    }

    @Test
    void missingAssetReturnsUnavailable() {
        properties.getOps().setDistrictCoverageEnabled(true);
        properties.getOps().getDistrictCoverage().setAssetPath(tempDir.resolve("missing.geojson").toString());
        properties.getOps().getDistrictCoverage().setExpectedSha256(miniatureSha);

        DistrictCoverageSection section = assembler.assemble(NOW, AGING, STALE);

        assertThat(section.status()).isEqualTo(DistrictCoverageStatus.UNAVAILABLE);
        assertThat(section.unavailableReason()).isEqualTo(MunicipalDistrictCoverageReason.ASSET_UNAVAILABLE);
        verify(queries, times(0)).listActiveFacilityProjections(anyInt(), anyLong(), anyLong(), any());
    }

    @Test
    void facilityLimitExceededReturnsUnavailable() throws Exception {
        enableWithMiniatureAsset();
        properties.getOps().getDistrictCoverage().setMaxFacilities(2);
        when(queries.listActiveFacilityProjections(eq(2), eq(AGING), eq(STALE), eq(NOW)))
                .thenReturn(List.of(
                        projection(27.005, 38.755),
                        projection(27.005, 38.755),
                        projection(27.005, 38.755)));

        DistrictCoverageSection section = assembler.assemble(NOW, AGING, STALE);

        assertThat(section.status()).isEqualTo(DistrictCoverageStatus.UNAVAILABLE);
        assertThat(section.unavailableReason()).isEqualTo(MunicipalDistrictCoverageReason.FACILITY_LIMIT);
    }

    @Test
    void secondCallWithinTtlUsesCacheWithoutRequerying() throws Exception {
        enableWithMiniatureAsset();
        when(queries.listActiveFacilityProjections(eq(10_000), eq(AGING), eq(STALE), eq(NOW)))
                .thenReturn(List.of(projection(27.005, 38.755)));

        assembler.assemble(NOW, AGING, STALE);
        DistrictCoverageSection cached = assembler.assemble(NOW, AGING, STALE);

        assertThat(cached.status()).isEqualTo(DistrictCoverageStatus.AVAILABLE);
        verify(queries, times(1)).listActiveFacilityProjections(anyInt(), anyLong(), anyLong(), any());
    }

    @Test
    void clearCacheForcesRecomputeOnNextCall() throws Exception {
        enableWithMiniatureAsset();
        when(queries.listActiveFacilityProjections(eq(10_000), eq(AGING), eq(STALE), eq(NOW)))
                .thenReturn(List.of(projection(27.005, 38.755)));

        assembler.assemble(NOW, AGING, STALE);
        assembler.clearCache();
        assembler.assemble(NOW, AGING, STALE);

        verify(queries, times(2)).listActiveFacilityProjections(anyInt(), anyLong(), anyLong(), any());
    }

    @Test
    void disabledFlagClearsCacheAndBypassesIt() throws Exception {
        enableWithMiniatureAsset();
        when(queries.listActiveFacilityProjections(eq(10_000), eq(AGING), eq(STALE), eq(NOW)))
                .thenReturn(List.of(projection(27.005, 38.755)));

        assembler.assemble(NOW, AGING, STALE);
        properties.getOps().setDistrictCoverageEnabled(false);

        DistrictCoverageSection disabled = assembler.assemble(NOW, AGING, STALE);

        assertThat(disabled.status()).isEqualTo(DistrictCoverageStatus.DISABLED);
        verify(queries, times(1)).listActiveFacilityProjections(anyInt(), anyLong(), anyLong(), any());

        properties.getOps().setDistrictCoverageEnabled(true);
        assembler.assemble(NOW, AGING, STALE);
        verify(queries, times(2)).listActiveFacilityProjections(anyInt(), anyLong(), anyLong(), any());
    }

    private void enableWithMiniatureAsset() throws Exception {
        Path asset = tempDir.resolve("ilceler-official-miniature.geojson");
        Files.write(asset, miniatureBytes);
        properties.getOps().setDistrictCoverageEnabled(true);
        MunicipalSourceProperties.DistrictCoverage cfg = properties.getOps().getDistrictCoverage();
        cfg.setAssetPath(asset.toString());
        cfg.setExpectedSha256(miniatureSha);
        cfg.setExpectedCount(30);
        cfg.setMaxFacilities(10_000);
        cfg.setCacheTtlSeconds(45);
    }

    private static MunicipalDistrictFacilityProjection projection(double longitude, double latitude) {
        return new MunicipalDistrictFacilityProjection(
                UUID.randomUUID(), latitude, longitude, false, false, false, false, false, false);
    }

    private static byte[] readFixture(String name) throws Exception {
        try (InputStream in = MunicipalDistrictCoverageAssemblerTest.class.getResourceAsStream(
                "/fixtures/municipal/boundary/" + name)) {
            assertThat(in).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
