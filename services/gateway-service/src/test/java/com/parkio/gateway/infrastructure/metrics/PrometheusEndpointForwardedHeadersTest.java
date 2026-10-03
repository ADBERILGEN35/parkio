package com.parkio.gateway.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * CL-F28 under the production forwarded-header setting. Every production model runs the gateway
 * with {@code SERVER_FORWARD_HEADERS_STRATEGY=framework}: Spring then applies and removes
 * {@code Forwarded} / {@code X-Forwarded-*} before any WebFilter runs, so those headers cannot tell
 * the gateway that Caddy relayed a request. Caddy marks every request it relays to the gateway with
 * {@code X-Parkio-Edge-Relay}, which survives; Prometheus scrapes the gateway directly without it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=framework")
@AutoConfigureObservability
@ActiveProfiles("test")
class PrometheusEndpointForwardedHeadersTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void anEdgeRelayedRequestGetsNoMetrics() {
        client().get().uri("/actuator/prometheus")
                .header("X-Parkio-Edge-Relay", "1")
                .header("X-Forwarded-For", "203.0.113.9")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "api.parkio.example")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void theInternalScrapeStillGetsMetrics() {
        byte[] body = client().get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(new String(body)).contains("parkio_gateway_rate_limit_rejected_count");
    }

    @Test
    void healthStillAnswersEdgeRelayedRequests() {
        client().get().uri("/actuator/health")
                .header("X-Parkio-Edge-Relay", "1")
                .header("X-Forwarded-For", "203.0.113.9")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(404));
    }

    private WebTestClient client() {
        return webTestClient.mutate().responseTimeout(Duration.ofSeconds(20)).build();
    }
}
