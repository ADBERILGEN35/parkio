package com.parkio.platform.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ApiErrorTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void preservesErrorEnvelopeShapeAndCorrelationTraceId() throws Exception {
        MDC.put("correlationId", "corr-123");
        try {
            ApiError body = ApiError.of(
                    "VALIDATION_FAILED",
                    "Request validation failed.",
                    List.of(new ApiError.FieldError("name", "must not be blank")),
                    Instant.parse("2026-06-26T10:15:30Z"));

            String json = objectMapper.writeValueAsString(body);

            assertThat(json).contains("\"code\":\"VALIDATION_FAILED\"");
            assertThat(json).contains("\"message\":\"Request validation failed.\"");
            assertThat(json).contains("\"traceId\":\"corr-123\"");
            assertThat(json).contains("\"fieldErrors\":[{\"field\":\"name\",\"message\":\"must not be blank\"}]");
            assertThat(json).contains("\"timestamp\":\"2026-06-26T10:15:30Z\"");
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void omitsNullFieldErrors() throws Exception {
        ApiError body = ApiError.of("INTERNAL_ERROR", "An unexpected error occurred.", Instant.parse("2026-06-26T10:15:30Z"));

        assertThat(objectMapper.writeValueAsString(body)).doesNotContain("fieldErrors");
    }
}
