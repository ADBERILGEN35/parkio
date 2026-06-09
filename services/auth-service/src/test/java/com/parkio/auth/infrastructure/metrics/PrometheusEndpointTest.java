package com.parkio.auth.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the Prometheus scrape endpoint is exposed and includes the custom Parkio
 * gauges/counters (micrometer-registry-prometheus + management exposure), while the
 * sensitive actuator endpoints stay hidden.
 *
 * <p>{@link AutoConfigureObservability} is required because Spring Boot tests disable
 * metrics export (and thus the prometheus endpoint) by default.
 */
@SpringBootTest
@AutoConfigureObservability
@AutoConfigureMockMvc
class PrometheusEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpointExposesParkioMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("parkio_outbox_unpublished_count")))
                .andExpect(content().string(Matchers.containsString("parkio_outbox_oldest_unpublished_age_seconds")))
                .andExpect(content().string(Matchers.containsString("parkio_inbox_processed_count")))
                .andExpect(content().string(Matchers.containsString("parkio_auth_login_success_count")))
                .andExpect(content().string(Matchers.containsString("parkio_auth_login_failure_count")));
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
        // Not 2xx: the endpoints are not exposed (the exact status is the service's
        // unknown-path handling, not an actuator response).
        mockMvc.perform(get("/actuator/env"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
        mockMvc.perform(get("/actuator/beans"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
    }
}
