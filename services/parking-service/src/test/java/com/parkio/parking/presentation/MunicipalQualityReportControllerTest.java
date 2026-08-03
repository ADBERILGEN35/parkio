package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.parking.application.quality.CoverageMetric;
import com.parkio.parking.application.quality.IntegrityGuardrails;
import com.parkio.parking.application.quality.IzumQualitySection;
import com.parkio.parking.application.quality.MunicipalQualityReport;
import com.parkio.parking.application.quality.MunicipalQualityReportService;
import com.parkio.parking.application.quality.NormalizedQualityReport;
import com.parkio.parking.application.quality.OsmQualitySection;
import com.parkio.parking.application.quality.ProvenanceFieldCoverage;
import com.parkio.parking.application.quality.RecentSyncRunSummary;
import com.parkio.parking.application.quality.SourceQualityDetail;
import com.parkio.parking.application.quality.SourceQualitySummary;
import com.parkio.parking.application.quality.UnknownQualityReportSourceException;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.registry.MunicipalQualityReportPolicy;
import com.parkio.parking.infrastructure.metrics.MunicipalQualityReportMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * DATA-WP-15 read-only operator quality report: auth gating, error mapping, bounded
 * metrics and the absence of any aggregate score / readiness verdict on the wire.
 *
 * <p>The {@code @ConditionalOnProperty} kill-switch cannot be exercised through a
 * standalone MockMvc setup (no Spring context evaluates conditions); it is covered by
 * {@link MunicipalQualityReportControllerAbsentWhenDisabledTest}.
 */
class MunicipalQualityReportControllerTest {
    private static final String OVERALL = "/api/v1/parking/admin/municipal/quality-report";
    private static final String SOURCES = OVERALL + "/sources";
    private static final String COUNTER = "parkio.municipal.ops.quality_report";
    private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");
    private static final List<String> FORBIDDEN_FIELDS = List.of(
            "qualityScore", "trustScore", "readinessScore", "linkingReadiness", "productionReady");

    private MunicipalQualityReportService service;
    private SimpleMeterRegistry registry;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MunicipalQualityReportService.class);
        registry = new SimpleMeterRegistry();
        MunicipalQualityReportController controller = new MunicipalQualityReportController(
                service, new MunicipalQualityReportMetrics(registry));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC)))
                .build();
    }

    @Test
    void unauthenticatedOverallReturns401() throws Exception {
        assertSafeError(mvc.perform(get(OVERALL)), 401, "UNAUTHORIZED");
        verifyNoInteractions(service);
        assertThat(registry.find(COUNTER).counters()).isEmpty();
    }

    @Test
    void blankRolesHeaderOverallReturns401() throws Exception {
        assertSafeError(mvc.perform(get(OVERALL).header("X-User-Roles", "   ")), 401, "UNAUTHORIZED");
        verifyNoInteractions(service);
    }

    @Test
    void unauthenticatedSourceDetailReturns401() throws Exception {
        assertSafeError(mvc.perform(get(SOURCES + "/" + MunicipalSourceIdentity.OSM)), 401, "UNAUTHORIZED");
        verifyNoInteractions(service);
    }

    @Test
    void userRoleOverallReturns403() throws Exception {
        assertSafeError(mvc.perform(get(OVERALL).header("X-User-Roles", "USER")), 403, "FORBIDDEN");
        verifyNoInteractions(service);
        assertThat(registry.find(COUNTER).counters()).isEmpty();
    }

    @Test
    void userRoleSourceDetailReturns403() throws Exception {
        assertSafeError(
                mvc.perform(get(SOURCES + "/" + MunicipalSourceIdentity.IZUM).header("X-User-Roles", "USER")),
                403, "FORBIDDEN");
        verifyNoInteractions(service);
    }

    @Test
    void adminOverallReturns200WithCoverageFacts() throws Exception {
        when(service.overallReport()).thenReturn(report());

        mvc.perform(get(OVERALL).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(MunicipalQualityReportPolicy.POLICY_VERSION))
                .andExpect(jsonPath("$.activeFacilities").value(10))
                .andExpect(jsonPath("$.sources", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.sources[0].sourceKey").value(MunicipalSourceIdentity.OSM))
                .andExpect(jsonPath("$.sources[0].sourceFamily").value(MunicipalSourceIdentity.FAMILY_OSM))
                .andExpect(jsonPath("$.osm.occupancySnapshotCount").value(0))
                .andExpect(jsonPath("$.osm.labelOutcomes.real_name_selected").value(2))
                .andExpect(jsonPath("$.izum.facilitiesWithOccupancy").value(4))
                .andExpect(jsonPath("$.integrity.duplicateSourceLinkGroups").value(0));

        assertCounter(MunicipalQualityReportMetrics.TYPE_OVERALL,
                MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                MunicipalQualityReportMetrics.FAMILY_NONE);
    }

    @Test
    void superAdminOverallReturns200() throws Exception {
        when(service.overallReport()).thenReturn(report());

        mvc.perform(get(OVERALL).header("X-User-Roles", "USER,SUPER_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(MunicipalQualityReportPolicy.POLICY_VERSION));

        assertCounter(MunicipalQualityReportMetrics.TYPE_OVERALL,
                MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                MunicipalQualityReportMetrics.FAMILY_NONE);
    }

    @Test
    void lowercaseAndPaddedAdminRoleIsAccepted() throws Exception {
        when(service.overallReport()).thenReturn(report());

        mvc.perform(get(OVERALL).header("X-User-Roles", " user , admin "))
                .andExpect(status().isOk());
    }

    @Test
    void adminOsmSourceDetailReturns200WithOsmSectionOnly() throws Exception {
        when(service.sourceReport(eq(MunicipalSourceIdentity.OSM), isNull()))
                .thenReturn(osmDetail());

        mvc.perform(get(SOURCES + "/" + MunicipalSourceIdentity.OSM).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.sourceKey").value(MunicipalSourceIdentity.OSM))
                .andExpect(jsonPath("$.osm").exists())
                .andExpect(jsonPath("$.izum").doesNotExist())
                .andExpect(jsonPath("$.recentRunLimit").value(20))
                .andExpect(jsonPath("$.recentRuns", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.recentRuns[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.recentRuns[0].correlationId").doesNotExist())
                .andExpect(jsonPath("$.recentRuns[0].payloadHash").doesNotExist());

        assertCounter(MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                MunicipalSourceIdentity.FAMILY_OSM);
    }

    @Test
    void adminIzumSourceDetailHonoursExplicitLimit() throws Exception {
        when(service.sourceReport(MunicipalSourceIdentity.IZUM, 5)).thenReturn(izumDetail(5));

        mvc.perform(get(SOURCES + "/" + MunicipalSourceIdentity.IZUM)
                        .param("limit", "5")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentRunLimit").value(5))
                .andExpect(jsonPath("$.izum").exists())
                .andExpect(jsonPath("$.osm").doesNotExist());

        assertCounter(MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                MunicipalSourceIdentity.FAMILY_IZUM);
    }

    @Test
    void unknownSourceReturns404() throws Exception {
        when(service.sourceReport(eq("izelman-open-parking-facilities"), isNull()))
                .thenThrow(new UnknownQualityReportSourceException(
                        "unsupported source key: izelman-open-parking-facilities"));

        assertSafeError(
                mvc.perform(get(SOURCES + "/izelman-open-parking-facilities").header("X-User-Roles", "ADMIN")),
                404, "NOT_FOUND");

        assertCounter(MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_NOT_FOUND,
                MunicipalSourceIdentity.FAMILY_IZELMAN);
    }

    @Test
    void unrecognisedSourceFamilyStillUsesABoundedMetricTag() throws Exception {
        when(service.sourceReport(eq("totally-unknown"), isNull()))
                .thenThrow(new UnknownQualityReportSourceException("unsupported source key: totally-unknown"));

        assertSafeError(mvc.perform(get(SOURCES + "/totally-unknown").header("X-User-Roles", "ADMIN")),
                404, "NOT_FOUND");

        assertCounter(MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_NOT_FOUND,
                MunicipalSourceIdentity.FAMILY_UNKNOWN);
        assertThat(registry.getMeters().stream().map(meter -> meter.getId().toString()).toList())
                .noneMatch(id -> id.contains("totally-unknown"));
    }

    @Test
    void invalidLimitReturns400() throws Exception {
        when(service.sourceReport(MunicipalSourceIdentity.OSM, 0))
                .thenThrow(new IllegalArgumentException("limit must be between 1 and 100"));

        assertSafeError(
                mvc.perform(get(SOURCES + "/" + MunicipalSourceIdentity.OSM)
                        .param("limit", "0")
                        .header("X-User-Roles", "ADMIN")),
                400, "BAD_REQUEST");

        assertCounter(MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_CLIENT_ERROR,
                MunicipalSourceIdentity.FAMILY_OSM);
    }

    @Test
    void limitAboveMaximumReturns400() throws Exception {
        when(service.sourceReport(MunicipalSourceIdentity.OSM, 101))
                .thenThrow(new IllegalArgumentException("limit must be between 1 and 100"));

        assertSafeError(
                mvc.perform(get(SOURCES + "/" + MunicipalSourceIdentity.OSM)
                        .param("limit", "101")
                        .header("X-User-Roles", "ADMIN")),
                400, "BAD_REQUEST");
    }

    @Test
    void nonNumericLimitReturns400() throws Exception {
        assertSafeError(
                mvc.perform(get(SOURCES + "/" + MunicipalSourceIdentity.OSM)
                        .param("limit", "abc")
                        .header("X-User-Roles", "ADMIN")),
                400, "MALFORMED_REQUEST");
        verifyNoInteractions(service);
    }

    @Test
    void unsupportedMethodsReturn405AndNeverReachTheService() throws Exception {
        assertSafeError(mvc.perform(put(OVERALL)
                .header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")), 405, "METHOD_NOT_ALLOWED");
        assertSafeError(mvc.perform(post(OVERALL)
                .header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")), 405, "METHOD_NOT_ALLOWED");
        assertSafeError(mvc.perform(delete(SOURCES + "/" + MunicipalSourceIdentity.OSM)
                .header("X-User-Roles", "ADMIN")), 405, "METHOD_NOT_ALLOWED");
        verifyNoInteractions(service);
    }

    @Test
    void unexpectedOverallErrorReturns500WithoutLeak() throws Exception {
        when(service.overallReport()).thenThrow(new RuntimeException(
                "SELECT * FROM municipal_parking_facilities jwt-token com.parkio.SecretRepository"));

        assertSafeError(mvc.perform(get(OVERALL).header("X-User-Roles", "ADMIN")), 500, "INTERNAL_ERROR")
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("jwt-token"))))
                .andExpect(content().string(not(containsString("com.parkio"))))
                .andExpect(content().string(not(containsString("municipal_parking_facilities"))));

        assertCounter(MunicipalQualityReportMetrics.TYPE_OVERALL,
                MunicipalQualityReportMetrics.OUTCOME_ERROR,
                MunicipalQualityReportMetrics.FAMILY_NONE);
    }

    @Test
    void unexpectedSourceErrorReturns500WithoutLeak() throws Exception {
        when(service.sourceReport(eq(MunicipalSourceIdentity.OSM), isNull()))
                .thenThrow(new IllegalStateException("connection refused to postgres://parkio:secret@db"));

        assertSafeError(
                mvc.perform(get(SOURCES + "/" + MunicipalSourceIdentity.OSM).header("X-User-Roles", "ADMIN")),
                500, "INTERNAL_ERROR")
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("postgres://"))));

        assertCounter(MunicipalQualityReportMetrics.TYPE_SOURCE,
                MunicipalQualityReportMetrics.OUTCOME_ERROR,
                MunicipalSourceIdentity.FAMILY_OSM);
    }

    @Test
    void overallResponseCarriesNoAggregateScoreOrReadinessVerdict() throws Exception {
        when(service.overallReport()).thenReturn(report());

        ResultActions actions = mvc.perform(get(OVERALL).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk());
        for (String forbidden : FORBIDDEN_FIELDS) {
            actions.andExpect(jsonPath("$." + forbidden).doesNotExist())
                    .andExpect(jsonPath("$.osm." + forbidden).doesNotExist())
                    .andExpect(jsonPath("$.izum." + forbidden).doesNotExist())
                    .andExpect(jsonPath("$.integrity." + forbidden).doesNotExist())
                    .andExpect(jsonPath("$.sources[0]." + forbidden).doesNotExist())
                    .andExpect(content().string(not(containsString(forbidden))));
        }
    }

    @Test
    void sourceDetailResponseCarriesNoAggregateScoreOrRawIngestIdentifiers() throws Exception {
        when(service.sourceReport(eq(MunicipalSourceIdentity.OSM), isNull())).thenReturn(osmDetail());

        ResultActions actions = mvc.perform(
                        get(SOURCES + "/" + MunicipalSourceIdentity.OSM).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk());
        for (String forbidden : FORBIDDEN_FIELDS) {
            actions.andExpect(jsonPath("$." + forbidden).doesNotExist())
                    .andExpect(jsonPath("$.summary." + forbidden).doesNotExist())
                    .andExpect(jsonPath("$.osm." + forbidden).doesNotExist())
                    .andExpect(content().string(not(containsString(forbidden))));
        }
        actions.andExpect(content().string(not(containsString("payloadHash"))))
                .andExpect(content().string(not(containsString("correlationId"))))
                .andExpect(content().string(not(containsString("schemaFingerprint"))))
                .andExpect(content().string(not(containsString("rawRecordHash"))));
    }

    @Test
    void zeroDenominatorCoverageSerialisesNullPercentageNotZero() throws Exception {
        when(service.overallReport()).thenReturn(emptyRegistryReport());

        mvc.perform(get(OVERALL).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeFacilities").value(0))
                .andExpect(jsonPath("$.sources[0].shareOfActiveFacilities.denominator").value(0))
                .andExpect(jsonPath("$.sources[0].shareOfActiveFacilities.percentage").doesNotExist())
                .andExpect(jsonPath("$.osm.nameBearingLabelCoverage.percentage").doesNotExist());
    }

    @Test
    void everyRequestIsReadOnlyOnTheServiceApi() throws Exception {
        when(service.overallReport()).thenReturn(report());
        mvc.perform(get(OVERALL).header("X-User-Roles", "ADMIN")).andExpect(status().isOk());

        assertThat(MunicipalQualityReportService.class.getDeclaredMethods())
                .filteredOn(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("overallReport", "sourceReport");
    }

    private void assertCounter(String reportType, String outcome, String family) {
        Counter counter = registry.find(COUNTER)
                .tag("report_type", reportType)
                .tag("outcome", outcome)
                .tag("source_family", family)
                .counter();
        assertThat(counter).as("counter %s/%s/%s", reportType, outcome, family).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    private ResultActions assertSafeError(ResultActions action, int expectedStatus, String expectedCode)
            throws Exception {
        return action.andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message", notNullValue()))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("at com.parkio"))))
                .andExpect(content().string(not(containsString("SELECT "))))
                .andExpect(content().string(not(containsString("Bearer "))));
    }

    private static MunicipalQualityReport report() {
        return new MunicipalQualityReport(
                MunicipalQualityReportPolicy.POLICY_VERSION,
                NOW,
                10L,
                List.of(summary(MunicipalSourceIdentity.OSM, 6L, 10L),
                        summary(MunicipalSourceIdentity.IZUM, 4L, 10L)),
                osmSection(6L),
                izumSection(4L),
                new IntegrityGuardrails(0, 0, 3, 2, 1, 0, 5, 4, 2, 0));
    }

    private static MunicipalQualityReport emptyRegistryReport() {
        return new MunicipalQualityReport(
                MunicipalQualityReportPolicy.POLICY_VERSION,
                NOW,
                0L,
                List.of(summary(MunicipalSourceIdentity.OSM, 0L, 0L),
                        summary(MunicipalSourceIdentity.IZUM, 0L, 0L)),
                osmSection(0L),
                izumSection(0L),
                new IntegrityGuardrails(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    private static SourceQualityDetail osmDetail() {
        return new SourceQualityDetail(
                MunicipalQualityReportPolicy.POLICY_VERSION,
                NOW,
                summary(MunicipalSourceIdentity.OSM, 6L, 10L),
                osmSection(6L),
                null,
                20,
                List.of(
                        new RecentSyncRunSummary("SUCCESS", null, NOW.minusSeconds(120), NOW.minusSeconds(110)),
                        new RecentSyncRunSummary("FAILED", "read_timeout",
                                NOW.minusSeconds(300), NOW.minusSeconds(290))));
    }

    private static SourceQualityDetail izumDetail(int limit) {
        return new SourceQualityDetail(
                MunicipalQualityReportPolicy.POLICY_VERSION,
                NOW,
                summary(MunicipalSourceIdentity.IZUM, 4L, 10L),
                null,
                izumSection(4L),
                limit,
                List.of(new RecentSyncRunSummary("SUCCESS", null, NOW.minusSeconds(60), NOW.minusSeconds(59))));
    }

    private static SourceQualitySummary summary(String sourceKey, long facilities, long total) {
        return new SourceQualitySummary(
                sourceKey,
                MunicipalSourceIdentity.familyOf(sourceKey),
                true,
                true,
                false,
                true,
                "HEALTHY",
                "SUCCESS",
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                60L,
                0,
                0,
                0,
                null,
                "LIVE",
                facilities,
                facilities,
                CoverageMetric.of(facilities, total),
                MunicipalQualityReportPolicy.PROVENANCE_FIELD_ORDER.stream()
                        .map(field -> ProvenanceFieldCoverage.of(field, facilities, total))
                        .toList());
    }

    private static OsmQualitySection osmSection(long facilities) {
        return new OsmQualitySection(
                false,
                false,
                true,
                "izmir-admin-izbb-2024-10-18-v1",
                "osm-label-v1",
                facilities,
                CoverageMetric.of(2L, facilities),
                1L,
                1L,
                0L,
                CoverageMetric.of(facilities, facilities),
                Map.of("real_name_selected", 2L, "operator_fallback", 3L, "legacy_technical", 1L),
                new NormalizedQualityReport(
                        true, 2L, 4L, 3L, "izmir-admin-izbb-2024-10-18-v1", "osm-label-v1",
                        Map.of("missing_geometry", 2L), Map.of("real_name_selected", 2L)));
    }

    private static IzumQualitySection izumSection(long facilities) {
        return new IzumQualitySection(
                true,
                false,
                300L,
                900L,
                facilities,
                facilities,
                CoverageMetric.of(2L, facilities),
                CoverageMetric.of(1L, facilities),
                CoverageMetric.of(1L, facilities),
                CoverageMetric.of(3L, facilities));
    }
}
