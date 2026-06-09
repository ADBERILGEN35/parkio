package com.parkio.auth.infrastructure.config;

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
 * Verifies OpenAPI docs are exposed when {@code parkio.openapi.enabled=true} and
 * include the bearer JWT security scheme. Internal endpoints must not appear.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void apiDocsExposeBearerSecurityScheme() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("info").path("title").asText()).contains("Auth");
        assertThat(root.path("components").path("securitySchemes").has("bearerAuth"));
        assertThat(root.at("/paths").toString()).doesNotContain("/internal/");
    }
}
