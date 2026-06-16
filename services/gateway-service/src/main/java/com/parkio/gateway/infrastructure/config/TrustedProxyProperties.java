package com.parkio.gateway.infrastructure.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code parkio.gateway.trusted-proxies}: the CIDR ranges / IPs of reverse proxies
 * whose {@code X-Forwarded-For} header may be trusted when deriving the client IP for
 * anonymous rate limiting (see {@link ClientIpResolver}).
 *
 * <p>Empty by default — the gateway trusts <strong>nothing</strong> and keys anonymous
 * requests on the socket peer, so direct-to-gateway deployments are unaffected. Behind the
 * hosted-beta Caddy proxy, set this to the Docker/proxy ranges via {@code PARKIO_TRUSTED_PROXIES}
 * (e.g. {@code 172.16.0.0/12,10.0.0.0/8,127.0.0.1/32}). Never widen this to public ranges.
 */
@ConfigurationProperties(prefix = "parkio.gateway")
public class TrustedProxyProperties {

    private List<String> trustedProxies = new ArrayList<>();

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }
}
