package com.parkio.user.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The downstream gateway-auth guard, incl. the internal status endpoint: rejects
 * requests without the correct {@code X-Gateway-Auth} secret and never gates actuator.
 */
class GatewayAuthFilterTest {

    private static final String SECRET = "shared-internal-secret-value";

    private final GatewayAuthFilter filter = new GatewayAuthFilter(SECRET, new ObjectMapper());

    @Test
    void rejectsMissingHeaderOnInternalEndpointWith401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/users/abc/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("GATEWAY_AUTH_REQUIRED");
    }

    @Test
    void allowsValidSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/users/abc/status");
        request.addHeader("X-Gateway-Auth", SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotGateActuatorHealth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }
}
