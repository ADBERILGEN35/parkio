package com.parkio.gateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls which gateway endpoints are reachable without authentication on the public
 * edge. Invite-production Level-B sets this false so internet clients (via Caddy) cannot
 * read {@code /actuator/info}. Loopback peers used by dark / public-staged smoke remain
 * readable via {@link com.parkio.gateway.infrastructure.security.PublicEndpoints}.
 */
@ConfigurationProperties(prefix = "parkio.gateway.public-surface")
public class GatewayPublicSurfaceProperties {

    /**
     * When false, {@code /actuator/info} is not on the public (non-loopback) surface.
     * Health probes remain public via {@link com.parkio.gateway.infrastructure.security.PublicEndpoints}.
     */
    private boolean actuatorInfoEnabled = true;
    /**
     * When true, anonymous Public Explore surfaces are allow-listed:
     * {@code GET /api/v1/public/explore/facilities} and
     * {@code GET /api/v1/public/geocoding/search}. Missing/false fails closed.
     */
    private boolean publicExploreEnabled;

    public boolean isActuatorInfoEnabled() {
        return actuatorInfoEnabled;
    }

    public void setActuatorInfoEnabled(boolean actuatorInfoEnabled) {
        this.actuatorInfoEnabled = actuatorInfoEnabled;
    }

    public boolean isPublicExploreEnabled() {
        return publicExploreEnabled;
    }

    public void setPublicExploreEnabled(boolean publicExploreEnabled) {
        this.publicExploreEnabled = publicExploreEnabled;
    }
}
