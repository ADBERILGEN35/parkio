package com.parkio.gateway.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.time.Duration;

/**
 * Verifies the gateway exposes the Prometheus scrape endpoint (and its custom edge
 * counter) while keeping sensitive actuator endpoints hidden.
 *
 * <p>{@link AutoConfigureObservability} is required because Spring Boot tests disable
 * metrics export (and thus the prometheus endpoint) by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
class PrometheusEndpointTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void prometheusEndpointExposesGatewayMetrics() {
        byte[] body = metricsClient().get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(new String(body))
                .contains("parkio_gateway_rate_limit_rejected_count");
    }

    /**
     * CL-F28: the public edge (Caddy) adds {@code X-Forwarded-*} to everything it relays;
     * Prometheus scrapes directly without them. A relayed request must not get the metrics.
     */
    @Test
    void prometheusEndpointRefusesEdgeRelayedRequests() {
        metricsClient().get().uri("/actuator/prometheus")
                .header("X-Forwarded-For", "203.0.113.9")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "api.parkio.example")
                .exchange()
                .expectStatus().isNotFound();
        metricsClient().get().uri("/actuator/prometheus")
                .header("Forwarded", "for=203.0.113.9;proto=https")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void healthStillAnswersEdgeRelayedRequests() {
        metricsClient().get().uri("/actuator/health")
                .header("X-Forwarded-For", "203.0.113.9")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(404));
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() {
        metricsClient().get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        metricsClient().get().uri("/actuator/beans").exchange().expectStatus().isNotFound();
    }

    private WebTestClient metricsClient() {
        return webTestClient.mutate()
                .responseTimeout(Duration.ofSeconds(20))
                .build();
    }
}
