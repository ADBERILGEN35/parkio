package com.parkio.parking.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.parking.application.geocoding.GeocodeResult;
import com.parkio.parking.application.geocoding.GeocodingService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP-contract tests for {@code GET /api/v1/geocoding/search} via a standalone
 * MockMvc (controller + the real {@link GlobalExceptionHandler}), so no Spring
 * context, datasource or Redis is needed. The {@link GeocodingService} is mocked.
 */
class GeocodingControllerTest {

    private static final String USER_ID = UUID.randomUUID().toString();

    private GeocodingService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(GeocodingService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-24T10:00:00Z"), ZoneOffset.UTC);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GeocodingController(service))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }

    @Test
    void returnsResultsWrappedInEnvelope() throws Exception {
        when(service.search(any(), any())).thenReturn(List.of(
                new GeocodeResult("123", "Konak Pier, İzmir", "Konak Pier", "Konak, İzmir", 38.42, 27.14)));

        mockMvc.perform(get("/api/v1/geocoding/search").param("q", "Konak Pier").header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].id").value("123"))
                .andExpect(jsonPath("$.results[0].primary").value("Konak Pier"))
                .andExpect(jsonPath("$.results[0].secondary").value("Konak, İzmir"))
                .andExpect(jsonPath("$.results[0].lat").value(38.42))
                .andExpect(jsonPath("$.results[0].lng").value(27.14));
    }

    @Test
    void providerDegradationSurfacesAsEmptyResults() throws Exception {
        when(service.search(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/geocoding/search").param("q", "nowhere").header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    void missingUserIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/geocoding/search").param("q", "Konak Pier"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
    }

    @Test
    void validationErrorMapsToBadRequest() throws Exception {
        when(service.search(any(), any())).thenThrow(new IllegalArgumentException("Query must be 3–256 chars."));

        mockMvc.perform(get("/api/v1/geocoding/search").param("q", "ab").header("X-User-Id", USER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
