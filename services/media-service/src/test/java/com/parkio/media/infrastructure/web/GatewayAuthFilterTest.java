package com.parkio.media.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The downstream gateway-auth guard: rejects requests without the correct
 * {@code X-Gateway-Auth} secret, lets valid ones through, never gates actuator, and
 * (for zero-downtime rotation) accepts both the current and any configured previous
 * secrets.
 */
class GatewayAuthFilterTest {

    private static final String SECRET = "shared-internal-secret-value";
    private static final String PREVIOUS_SECRET = "previous-internal-secret-value";

    private final GatewayAuthFilter filter = new GatewayAuthFilter(SECRET, new ObjectMapper());

    @Test
    void rejectsMissingHeaderWith401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/media/upload");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("GATEWAY_AUTH_REQUIRED");
    }

    @Test
    void allowsValidSecret() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/media/upload");
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

    @Test
    void rejectsWrongSecretWith401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/media/upload");
        request.addHeader("X-Gateway-Auth", "totally-wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void acceptsBothCurrentAndPreviousSecretsDuringRotation() throws Exception {
        GatewayAuthFilter rotating = new GatewayAuthFilter(SECRET, PREVIOUS_SECRET, new ObjectMapper());

        assertThat(passesWith(rotating, SECRET)).isTrue();          // new/current secret
        assertThat(passesWith(rotating, PREVIOUS_SECRET)).isTrue(); // old secret still accepted
        assertThat(passesWith(rotating, "neither-of-them")).isFalse();
    }

    @Test
    void acceptsMultiplePreviousSecretsAndIgnoresBlankEntries() throws Exception {
        GatewayAuthFilter rotating =
                new GatewayAuthFilter(SECRET, " prev-a , , prev-b ", new ObjectMapper());

        assertThat(passesWith(rotating, SECRET)).isTrue();
        assertThat(passesWith(rotating, "prev-a")).isTrue();
        assertThat(passesWith(rotating, "prev-b")).isTrue();
        assertThat(passesWith(rotating, "")).isFalse();
        assertThat(passesWith(rotating, " ")).isFalse();
    }

    @Test
    void failsClosedWhenCurrentSecretBlank() {
        assertThatThrownBy(() -> new GatewayAuthFilter("   ", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARKIO_GATEWAY_INTERNAL_SECRET");
        assertThatThrownBy(() -> new GatewayAuthFilter("", "still-blank-current", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARKIO_GATEWAY_INTERNAL_SECRET");
    }

    private static boolean passesWith(GatewayAuthFilter filter, String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/media/upload");
        request.addHeader("X-Gateway-Auth", headerValue);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        return chain.getRequest() != null;
    }
}
