package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.geocoding.GeocodeResult;
import com.parkio.parking.application.geocoding.GeocodingService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicGeocodingControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ENVELOPE_KEYS = Set.of("results");
    private static final Set<String> RESULT_KEYS =
            Set.of("id", "displayName", "primary", "secondary", "lat", "lng");

    private GeocodingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(GeocodingService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-09T10:00:00Z"), ZoneOffset.UTC);
        mvc = MockMvcBuilders
                .standaloneSetup(new PublicGeocodingController(service))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }

    @Test
    void searchReturnsNoStoreCacheAndSafeEnvelope() throws Exception {
        when(service.search(eq("Alsancak"), eq(null))).thenReturn(List.of(
                new GeocodeResult("n1", "Alsancak, İzmir", "Alsancak", "İzmir", 38.439, 27.145)));

        MvcResult result = mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Alsancak"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].id").value("n1"))
                .andExpect(jsonPath("$.results[0].primary").value("Alsancak"))
                .andExpect(jsonPath("$.results[0].lat").value(38.439))
                .andExpect(jsonPath("$.results[0].lng").value(27.145))
                .andReturn();

        assertExactPublicSerialization(result.getResponse().getContentAsString());
        verify(service).search("Alsancak", null);
    }

    @Test
    void usesDefaultServiceLimitWhenLimitOmitted() throws Exception {
        when(service.search(eq("Konak"), eq(null))).thenReturn(List.of());
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Konak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray());
        verify(service).search("Konak", null);
    }

    @Test
    void acceptsMaxLimitBoundary() throws Exception {
        when(service.search(eq("Forum"), eq(10))).thenReturn(List.of());
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Forum").param("limit", "10"))
                .andExpect(status().isOk());
        verify(service).search("Forum", 10);
    }

    @Test
    void zeroResultsAreOkEmptyList() throws Exception {
        when(service.search(any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "nowhere-xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    void providerDegradationSurfacesAsEmptyResults() throws Exception {
        when(service.search(any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Alsancak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    void trimsQueryBeforeServiceCall() throws Exception {
        when(service.search(eq("Alsancak"), eq(null))).thenReturn(List.of());
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "  Alsancak  "))
                .andExpect(status().isOk());
        verify(service).search("Alsancak", null);
    }

    @Test
    void turkishUnicodeQueryIsAccepted() throws Exception {
        when(service.search(eq("Karşıyaka"), eq(null))).thenReturn(List.of(
                new GeocodeResult("tr", "Karşıyaka, İzmir", "Karşıyaka", "İzmir", 38.46, 27.11)));
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Karşıyaka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].primary").value("Karşıyaka"));
    }

    @Test
    void rejectsMissingQuery() throws Exception {
        mvc.perform(get("/api/v1/public/geocoding/search"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBlankQuery() throws Exception {
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsTooShortQuery() throws Exception {
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "ab"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsMinimumQueryLengthBoundary() throws Exception {
        when(service.search(eq("abc"), eq(null))).thenReturn(List.of());
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "abc"))
                .andExpect(status().isOk());
        verify(service).search("abc", null);
    }

    @Test
    void acceptsMaximumQueryLengthBoundary() throws Exception {
        String max = "a".repeat(256);
        when(service.search(eq(max), eq(null))).thenReturn(List.of());
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", max))
                .andExpect(status().isOk());
        verify(service).search(max, null);
    }

    @Test
    void rejectsTooLongQueryWithoutTruncation() throws Exception {
        String over = "a".repeat(257);
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", over))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedLimitFailClosed() throws Exception {
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Alsancak").param("limit", "11"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Alsancak").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidLimit() throws Exception {
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Alsancak").param("limit", "1.5"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Alsancak").param("limit", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownParameters() throws Exception {
        mvc.perform(get("/api/v1/public/geocoding/search")
                        .param("q", "Alsancak")
                        .param("countrycodes", "tr"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/geocoding/search")
                        .param("q", "Alsancak")
                        .param("provider", "nominatim"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/geocoding/search")
                        .param("q", "Alsancak")
                        .param("viewbox", "1,2,3,4"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateParameters() throws Exception {
        mvc.perform(get("/api/v1/public/geocoding/search?q=a&q=b"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/geocoding/search?q=Alsancak&limit=5&limit=8"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Alsancak\u0000Pier",
            "Konak\nPier",
            "Forum\tBornova",
            "Alsancak\r\n<script>"
    })
    void rejectsControlCharacters(String query) throws Exception {
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", query))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "'; DROP TABLE users; --",
            "https://evil.example/path",
            "\"quoted\"",
            "Alsancak & Konak"
    })
    void treatsHostileStringsAsPlainTextOrRejectsViaServiceBounds(String query) throws Exception {
        when(service.search(eq(query), eq(null))).thenReturn(List.of());
        mvc.perform(get("/api/v1/public/geocoding/search").param("q", query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray());
        verify(service).search(query, null);
    }

    @Test
    void filtersMalformedCoordinatesFromPublicResponse() throws Exception {
        when(service.search(any(), any())).thenReturn(List.of(
                new GeocodeResult("bad", "Bad", "Bad", "", Double.NaN, 27.14),
                new GeocodeResult("ok", "Ok", "Ok", "İzmir", 38.42, 27.14),
                new GeocodeResult("oor", "OOR", "OOR", "", 91.0, 27.14)));

        mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Alsancak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].id").value("ok"));
    }

    @Test
    void spoofedIdentityHeadersDoNotPersonalizeOrChangeBehavior() throws Exception {
        when(service.search(eq("Alsancak"), eq(null))).thenReturn(List.of(
                new GeocodeResult("n1", "Alsancak", "Alsancak", "İzmir", 38.43, 27.14)));

        String anonymous = mvc.perform(get("/api/v1/public/geocoding/search").param("q", "Alsancak"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String spoofed = mvc.perform(get("/api/v1/public/geocoding/search")
                        .param("q", "Alsancak")
                        .header("X-User-Id", "forged-user")
                        .header("X-Role", "ADMIN")
                        .header("Authorization", "Bearer forged-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(spoofed).isEqualTo(anonymous);
        assertThat(anonymous).doesNotContain("favourite");
        assertThat(anonymous).doesNotContain("recent");
        assertThat(anonymous).doesNotContain("saved");
    }

    @Test
    void controllerIsAbsentUnlessPublicExploreFlagIsTrue() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(GeocodingService.class, () -> mock(GeocodingService.class))
                .withUserConfiguration(ControllerConfiguration.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(PublicGeocodingController.class));
        runner.withPropertyValues("parkio.public-explore.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PublicGeocodingController.class));
    }

    private static void assertExactPublicSerialization(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        assertThat(root.isObject()).isTrue();
        assertThat(fieldNames(root)).containsExactlyInAnyOrderElementsOf(ENVELOPE_KEYS);
        assertThat(root.get("results").isArray()).isTrue();
        for (JsonNode item : root.get("results")) {
            assertThat(fieldNames(item)).containsExactlyInAnyOrderElementsOf(RESULT_KEYS);
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PublicGeocodingController.class)
    static class ControllerConfiguration {
    }
}
