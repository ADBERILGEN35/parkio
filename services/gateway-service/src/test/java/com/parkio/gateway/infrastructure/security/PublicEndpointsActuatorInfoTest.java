package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.infrastructure.config.GatewayPublicSurfaceProperties;
import java.net.InetSocketAddress;
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
    void actuatorInfoProtectedWhenDisabledForNonLoopback() {
        GatewayPublicSurfaceProperties properties = new GatewayPublicSurfaceProperties();
        properties.setActuatorInfoEnabled(false);
        PublicEndpoints endpoints = new PublicEndpoints(properties);
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/info")
                .remoteAddress(new InetSocketAddress("172.18.0.5", 44300))
                .build();
        assertThat(endpoints.isPublic(request)).isFalse();
    }

    @Test
    void actuatorInfoPublicOnLoopbackWhenDisabled() {
        GatewayPublicSurfaceProperties properties = new GatewayPublicSurfaceProperties();
        properties.setActuatorInfoEnabled(false);
        PublicEndpoints endpoints = new PublicEndpoints(properties);
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/info")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 54321))
                .build();
        assertThat(endpoints.isPublic(request)).isTrue();
    }

    @Test
    void actuatorInfoProtectedWhenDisabledAndRemoteAddressAbsent() {
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

    @Test
    void sensitiveActuatorPathsRemainProtectedWhenInfoDisabled() {
        GatewayPublicSurfaceProperties properties = new GatewayPublicSurfaceProperties();
        properties.setActuatorInfoEnabled(false);
        PublicEndpoints endpoints = new PublicEndpoints(properties);
        assertThat(endpoints.isPublic(MockServerHttpRequest.get("/actuator/env")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 1))
                .build())).isFalse();
        assertThat(endpoints.isPublic(MockServerHttpRequest.get("/actuator/configprops")
                .remoteAddress(new InetSocketAddress("172.18.0.5", 1))
                .build())).isFalse();
        assertThat(endpoints.isPublic(MockServerHttpRequest.get("/actuator/prometheus")
                .remoteAddress(new InetSocketAddress("172.18.0.5", 1))
                .build())).isFalse();
    }
}
