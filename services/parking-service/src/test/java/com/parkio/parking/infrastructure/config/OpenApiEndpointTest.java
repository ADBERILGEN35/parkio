package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies OpenAPI docs are exposed and describe parking endpoints with security.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void apiDocsExposeBearerSecuritySchemeAndParkingSessionContract() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("info").path("title").asText()).contains("Parking");
        assertThat(root.path("components").path("securitySchemes").has("bearerAuth"));
        assertThat(root.path("components").path("securitySchemes").has("gatewayAuth")).isFalse();
        assertThat(root.toString()).doesNotContain("X-Gateway-Auth");
        assertThat(root.at("/paths").toString()).contains("/api/v1/parking/spots");
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions/post/operationId").asText())
                .isEqualTo("startParkingSession");
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions~1active/get/operationId").asText())
                .isEqualTo("getActiveParkingSession");
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions~1{sessionId}~1complete/post/operationId").asText())
                .isEqualTo("completeParkingSession");
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions~1{sessionId}~1cancel/post/operationId").asText())
                .isEqualTo("cancelParkingSession");
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions~1history/get/operationId").asText())
                .isEqualTo("getParkingSessionHistory");
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions~1{sessionId}/delete/operationId").asText())
                .isEqualTo("deleteParkingSession");
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions~1history/delete/operationId").asText())
                .isEqualTo("deleteParkingSessionHistory");
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions~1{sessionId}/delete/responses").has("204"))
                .isTrue();
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions~1{sessionId}/delete/responses").has("409"))
                .isTrue();
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions~1history/delete/responses").has("204"))
                .isTrue();
        assertThat(root.toString()).contains("PARKING_SESSION_NOT_TERMINAL");

        JsonNode claimOperation = root.at("/paths/~1api~1v1~1parking~1spots~1{spotId}~1claim/post");
        assertThat(claimOperation.path("operationId").asText()).isEqualTo("claimSpot");
        assertThat(claimOperation.path("requestBody").isMissingNode()).isTrue();
        assertThat(claimOperation.path("parameters").findValuesAsText("name"))
                .contains("spotId", "Idempotency-Key")
                .doesNotContain("X-User-Id", "X-Gateway-Auth");
        JsonNode idempotencyHeader = java.util.stream.StreamSupport.stream(
                        claimOperation.path("parameters").spliterator(), false)
                .filter(node -> "Idempotency-Key".equals(node.path("name").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(idempotencyHeader.path("required").asBoolean()).isTrue();
        assertThat(idempotencyHeader.path("schema").path("minLength").asInt()).isEqualTo(8);
        assertThat(idempotencyHeader.path("schema").path("maxLength").asInt()).isEqualTo(128);
        assertThat(claimOperation.path("responses").fieldNames())
                .toIterable()
                .contains("200", "400", "401", "403", "404", "409", "429", "500", "503");
        for (String responseCode : List.of("200", "400", "401", "403", "404", "409", "429", "500", "503")) {
            JsonNode cacheControl = claimOperation.path("responses")
                    .path(responseCode)
                    .path("headers")
                    .path("Cache-Control")
                    .path("schema");
            assertThat(cacheControl.path("type").asText()).isEqualTo("string");
            assertThat(cacheControl.path("example").asText()).isEqualTo("no-store");
        }
        assertThat(claimOperation.toString()).contains(
                "COMMUNITY",
                "Cache-Control",
                "no-store",
                "ACTIVE_PARKING_SESSION_EXISTS",
                "SPOT_NOT_FOUND",
                "SPOT_EXPIRED",
                "SPOT_NOT_CLAIMABLE",
                "IDEMPOTENCY_KEY_INVALID",
                "IDEMPOTENCY_KEY_CONFLICT",
                "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "RATE_LIMITED",
                "CONFLICT");

        JsonNode startOperation = root.at("/paths/~1api~1v1~1parking~1sessions/post");
        assertThat(startOperation.path("responses").has("201")).isTrue();
        assertThat(startOperation.path("responses").has("409")).isTrue();
        assertThat(startOperation.path("parameters").toString())
                .contains("Idempotency-Key", "\"required\":true");
        assertThat(startOperation.path("requestBody").toString())
                .contains("manualStartWithoutFee", "manualStartWithFee", "125.50");

        List<String> parkingSessionOperations = List.of(
                "/paths/~1api~1v1~1parking~1sessions/post",
                "/paths/~1api~1v1~1parking~1sessions~1active/get",
                "/paths/~1api~1v1~1parking~1sessions~1{sessionId}~1complete/post",
                "/paths/~1api~1v1~1parking~1sessions~1{sessionId}~1cancel/post",
                "/paths/~1api~1v1~1parking~1sessions~1history/get",
                "/paths/~1api~1v1~1parking~1sessions~1{sessionId}/delete",
                "/paths/~1api~1v1~1parking~1sessions~1history/delete");
        for (String operationPointer : parkingSessionOperations) {
            assertThat(root.at(operationPointer + "/parameters").findValuesAsText("name"))
                    .as("gateway-injected identity header must be hidden for %s", operationPointer)
                    .doesNotContain("X-User-Id");
        }

        JsonNode requestFee = root.at("/components/schemas/StartParkingSessionRequest/properties/estimatedFee");
        assertThat(requestFee.path("type").toString()).contains("string", "null");
        assertThat(requestFee.path("pattern").asText()).isNotBlank();
        assertThat(requestFee.path("maxLength").asInt()).isEqualTo(32);
        assertThat(root.at("/components/schemas/StartParkingSessionRequest/additionalProperties").asBoolean())
                .isFalse();
        assertThat(root.at("/components/schemas/ParkingSessionResponse/properties/endedAt/type").toString())
                .contains("string", "null");
        assertThat(root.at("/components/schemas/ParkingSessionResponse/properties/estimatedFee/type").toString())
                .contains("string", "null");
        assertThat(root.at("/components/schemas/ParkingSessionResponse/required").toString())
                .contains("endedAt", "estimatedFee");
        assertThat(root.at("/components/schemas/ParkingSessionHistoryResponse/required").toString())
                .contains("items", "nextCursor");
        assertThat(root.at("/paths/~1api~1v1~1parking~1sessions~1active/get/responses").has("204"))
                .isTrue();
        assertThat(root.toString()).contains(
                "ACTIVE_PARKING_SESSION_EXISTS",
                "PARKING_SESSION_NOT_FOUND",
                "PARKING_SESSION_NOT_ACTIVE",
                "INVALID_PARKING_SESSION_CURSOR",
                "IDEMPOTENCY_KEY_CONFLICT",
                "VALIDATION_FAILED",
                "CONFLICT",
                "RATE_LIMITED",
                "emptyHistory",
                "paginatedHistory");
        assertThat(root.at("/paths").toString()).doesNotContain("/internal/");
    }
}
