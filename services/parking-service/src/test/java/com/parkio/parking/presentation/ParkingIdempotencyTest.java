package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ParkingIdempotencyTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM parking_spot_verifications");
        jdbc.update("DELETE FROM parking_spot_status_history");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM parking_spots");
    }

    @Test
    void missingKeyIsRejectedForCreateClaimAndVerify() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        String traceId = UUID.randomUUID().toString();

        mockMvc.perform(authenticated(post("/api/v1/parking/spots"), userId)
                        .header("X-Correlation-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), "first")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
                .andExpect(jsonPath("$.traceId").value(traceId));

        mockMvc.perform(authenticated(post("/api/v1/parking/spots/{spotId}/claim", spotId), userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mockMvc.perform(authenticated(post("/api/v1/parking/spots/{spotId}/verify", spotId), userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"AVAILABLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void errorWithoutCorrelationHeaderGetsGeneratedTraceId() throws Exception {
        MvcResult result = mockMvc.perform(authenticated(post("/api/v1/parking/spots"), UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), "generated-trace")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();

        String traceId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.traceId");
        assertThat(UUID.fromString(traceId)).isNotNull();
        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isEqualTo(traceId);
    }

    @Test
    void createRetryReturnsSameSpotWithoutDuplicateRowsOrEvents() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String body = createBody(UUID.randomUUID(), "retry-safe");

        MvcResult first = create(userId, key, body);
        String spotId = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(authenticated(post("/api/v1/parking/spots"), userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(spotId));

        assertThat(count("parking_spots")).isEqualTo(1);
        assertThat(count("parking_spot_status_history")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void claimRetryDoesNotDuplicateStatusHistoryOrEvent() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID claimer = UUID.randomUUID();
        UUID spotId = createdSpotId(owner);
        String key = UUID.randomUUID().toString();

        claim(claimer, spotId, key).andExpect(status().isOk());
        claim(claimer, spotId, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"));

        assertThat(countWhere("parking_spot_status_history", "reason = 'CLAIMED'")).isEqualTo(1);
        assertThat(countWhere("outbox_events", "event_type = 'ParkingSpotClaimed'")).isEqualTo(1);
    }

    @Test
    void verifyRetryDoesNotDuplicateVerificationOrEvent() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID verifier = UUID.randomUUID();
        UUID spotId = createdSpotId(owner);
        String key = UUID.randomUUID().toString();

        verify(verifier, spotId, key, "AVAILABLE").andExpect(status().isOk());
        verify(verifier, spotId, key, "AVAILABLE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        assertThat(count("parking_spot_verifications")).isEqualTo(1);
        assertThat(countWhere("parking_spot_status_history", "reason = 'VERIFICATION_AVAILABLE'"))
                .isEqualTo(1);
        assertThat(countWhere("outbox_events", "event_type = 'ParkingSpotVerified'")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyOrPathReturnsConflict() throws Exception {
        UUID userId = UUID.randomUUID();
        String bodyKey = UUID.randomUUID().toString();

        create(userId, bodyKey, createBody(UUID.randomUUID(), "first"));
        mockMvc.perform(authenticated(post("/api/v1/parking/spots"), userId)
                        .header("Idempotency-Key", bodyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), "different")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        UUID owner = UUID.randomUUID();
        UUID spotId = createdSpotId(owner);
        UUID actor = UUID.randomUUID();
        String pathKey = UUID.randomUUID().toString();
        verify(actor, spotId, pathKey, "AVAILABLE").andExpect(status().isOk());
        claim(actor, spotId, pathKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    private MvcResult create(UUID userId, String key, String body) throws Exception {
        return mockMvc.perform(authenticated(post("/api/v1/parking/spots"), userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private UUID createdSpotId(UUID owner) throws Exception {
        MvcResult result = create(
                owner, UUID.randomUUID().toString(), createBody(UUID.randomUUID(), "setup"));
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id"));
    }

    private org.springframework.test.web.servlet.ResultActions claim(
            UUID userId, UUID spotId, String key) throws Exception {
        return mockMvc.perform(authenticated(
                        post("/api/v1/parking/spots/{spotId}/claim", spotId), userId)
                .header("Idempotency-Key", key));
    }

    private org.springframework.test.web.servlet.ResultActions verify(
            UUID userId, UUID spotId, String key, String result) throws Exception {
        return mockMvc.perform(authenticated(
                        post("/api/v1/parking/spots/{spotId}/verify", spotId), userId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"" + result + "\"}"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            UUID userId) {
        return request.header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-User-Id", userId);
    }

    private String createBody(UUID mediaId, String description) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "mediaId", mediaId,
                "latitude", 41.0082,
                "longitude", 28.9784,
                "description", description,
                "manualLocationEdited", false,
                "suitableVehicleTypes", new String[]{"SEDAN"},
                "parkingContext", "STREET_PARKING",
                "legalStatus", "LEGAL",
                "violationReasons", new String[0]));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long countWhere(String table, String condition) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + condition, Long.class);
    }
}
