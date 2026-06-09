package com.parkio.parking.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The downstream gateway-auth guard: rejects requests without the correct
 * {@code X-Gateway-Auth} secret, lets valid ones through, and never gates actuator.
 */
class GatewayAuthFilterTest {

    private static final String SECRET = "shared-internal-secret-value";

    private final GatewayAuthFilter filter = new GatewayAuthFilter(SECRET, new ObjectMapper());

    @Test
    void rejectsMissingHeaderWith401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/parking/spots/nearby");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull(); // not forwarded
        assertThat(response.getContentAsString()).contains("GATEWAY_AUTH_REQUIRED");
    }

    @Test
    void rejectsWrongSecretWith401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/parking/spots/nearby");
        request.addHeader("X-Gateway-Auth", "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void allowsValidSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/parking/spots/nearby");
        request.addHeader("X-Gateway-Auth", SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request); // forwarded downstream
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotGateActuatorHealth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request); // health probe allowed without secret
    }
}
