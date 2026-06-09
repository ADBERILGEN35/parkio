package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void apiDocsExposeBearerSecuritySchemeAndParkingPaths() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("info").path("title").asText()).contains("Parking");
        assertThat(root.path("components").path("securitySchemes").has("bearerAuth"));
        assertThat(root.at("/paths").toString()).contains("/api/v1/parking/spots");
        assertThat(root.at("/paths").toString()).doesNotContain("/internal/");
    }
}
