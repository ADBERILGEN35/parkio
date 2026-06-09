package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.parkio.parking.application.port.MediaAccessPort;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * HTTP-contract tests for {@code GET /api/v1/parking/spots/{spotId}/media-access-url}:
 * any authenticated user gets a signed URL for a visible spot, hidden/filled spots
 * answer 404 (no enumeration), a media-service outage degrades to 503, and the
 * response never exposes storage internals.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpotMediaAccessUrlTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private MediaAccessPort mediaAccess;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM parking_spot_verifications");
        jdbc.update("DELETE FROM parking_spot_status_history");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM parking_spots");
        reset(mediaAccess);
        when(mediaAccess.requestAccessUrl(any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> new MediaAccessPort.MediaAccessGrant(
                        invocation.getArgument(0),
                        "https://minio.local/signed/" + invocation.getArgument(0) + "?sig=test",
                        Instant.parse("2026-06-09T12:05:00Z")));
    }

    @Test
    void visibleSpotReturnsSignedUrlForNonOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        String spotId = createSpot(owner, mediaId);

        MvcResult result = mockMvc.perform(
                        authenticated(get("/api/v1/parking/spots/{spotId}/media-access-url", spotId),
                                UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spotId").value(spotId))
                .andExpect(jsonPath("$.mediaId").value(mediaId.toString()))
                .andExpect(jsonPath("$.accessUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("bucket", "objectKey", "object_key", "checksum");
    }

    @Test
    void filledSpotMediaAccessIsNotFound() throws Exception {
        UUID owner = UUID.randomUUID();
        String spotId = createSpot(owner, UUID.randomUUID());
        UUID claimer = UUID.randomUUID();
        mockMvc.perform(authenticated(post("/api/v1/parking/spots/{spotId}/claim", spotId), claimer)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"));

        mockMvc.perform(authenticated(get("/api/v1/parking/spots/{spotId}/media-access-url", spotId),
                        UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPOT_NOT_FOUND"));
    }

    @Test
    void ownerStillGetsMediaAccessForOwnFilledSpot() throws Exception {
        UUID owner = UUID.randomUUID();
        String spotId = createSpot(owner, UUID.randomUUID());
        mockMvc.perform(authenticated(post("/api/v1/parking/spots/{spotId}/claim", spotId), UUID.randomUUID())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        mockMvc.perform(authenticated(get("/api/v1/parking/spots/{spotId}/media-access-url", spotId), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spotId").value(spotId));
    }

    @Test
    void unknownSpotMediaAccessIsNotFound() throws Exception {
        mockMvc.perform(authenticated(
                        get("/api/v1/parking/spots/{spotId}/media-access-url", UUID.randomUUID()),
                        UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPOT_NOT_FOUND"));
    }

    @Test
    void mediaServiceOutageDegradesToServiceUnavailable() throws Exception {
        UUID owner = UUID.randomUUID();
        String spotId = createSpot(owner, UUID.randomUUID());
        when(mediaAccess.requestAccessUrl(any(UUID.class), any(UUID.class)))
                .thenThrow(new ParkingException(ParkingErrorCode.MEDIA_ACCESS_UNAVAILABLE,
                        "Spot photo is temporarily unavailable."));

        mockMvc.perform(authenticated(get("/api/v1/parking/spots/{spotId}/media-access-url", spotId),
                        UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEDIA_ACCESS_UNAVAILABLE"));
    }

    @Test
    void mediaAccessUrlRequiresUserId() throws Exception {
        mockMvc.perform(get("/api/v1/parking/spots/{spotId}/media-access-url", UUID.randomUUID())
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
    }

    private String createSpot(UUID owner, UUID mediaId) throws Exception {
        MvcResult result = mockMvc.perform(authenticated(post("/api/v1/parking/spots"), owner)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mediaId", mediaId,
                                "latitude", 41.0082,
                                "longitude", 28.9784,
                                "description", "spot with photo",
                                "manualLocationEdited", false,
                                "suitableVehicleTypes", new String[]{"SEDAN"},
                                "parkingContext", "STREET_PARKING",
                                "legalStatus", "LEGAL",
                                "violationReasons", new String[0]))))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, UUID userId) {
        return request.header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-User-Id", userId);
    }
}
