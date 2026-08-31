package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.infrastructure.config.GatewayPublicSurfaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class PublicEndpointsActuatorInfoTest {

    @Test
    void actuatorInfoPublicWhenEnabled() {
        GatewayPublicSurfaceProperties properties = new GatewayPublicSurfaceProperties();
        properties.setActuatorInfoEnabled(true);
        PublicEndpoints endpoints = new PublicEndpoints(properties);
        assertThat(endpoints.isPublic(MockServerHttpRequest.get("/actuator/info").build())).isTrue();
    }

    @Test
    void actuatorInfoProtectedWhenDisabled() {
        GatewayPublicSurfaceProperties properties = new GatewayPublicSurfaceProperties();
        properties.setActuatorInfoEnabled(false);
        PublicEndpoints endpoints = new PublicEndpoints(properties);
        assertThat(endpoints.isPublic(MockServerHttpRequest.get("/actuator/info").build())).isFalse();
    }

    @Test
    void healthRemainsPublicWhenInfoDisabled() {
        GatewayPublicSurfaceProperties properties = new GatewayPublicSurfaceProperties();
        properties.setActuatorInfoEnabled(false);
        PublicEndpoints endpoints = new PublicEndpoints(properties);
        assertThat(endpoints.isPublic(MockServerHttpRequest.get("/actuator/health").build())).isTrue();
        assertThat(endpoints.isPublic(MockServerHttpRequest.get("/actuator/health/liveness").build())).isTrue();
    }

    @Test
    void registerRemainsPublicWhenInfoDisabled() {
        GatewayPublicSurfaceProperties properties = new GatewayPublicSurfaceProperties();
        properties.setActuatorInfoEnabled(false);
        PublicEndpoints endpoints = new PublicEndpoints(properties);
        assertThat(endpoints.isPublic(MockServerHttpRequest.method(HttpMethod.POST, "/api/v1/auth/register").build()))
                .isTrue();
    }
}
