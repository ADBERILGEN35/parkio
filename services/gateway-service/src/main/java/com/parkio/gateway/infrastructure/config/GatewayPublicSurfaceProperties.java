package com.parkio.gateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls which gateway endpoints are reachable without authentication on the public
 * edge. Dark invite-production acceptance reads {@code /actuator/info} on the loopback
 * gateway; public cutover should disable that surface.
 */
@ConfigurationProperties(prefix = "parkio.gateway.public-surface")
public class GatewayPublicSurfaceProperties {

    /**
     * When false, {@code /actuator/info} requires authentication like any protected route.
     * Health probes remain public via {@link com.parkio.gateway.infrastructure.security.PublicEndpoints}.
     */
    private boolean actuatorInfoEnabled = true;

    public boolean isActuatorInfoEnabled() {
        return actuatorInfoEnabled;
    }

    public void setActuatorInfoEnabled(boolean actuatorInfoEnabled) {
        this.actuatorInfoEnabled = actuatorInfoEnabled;
    }
}
