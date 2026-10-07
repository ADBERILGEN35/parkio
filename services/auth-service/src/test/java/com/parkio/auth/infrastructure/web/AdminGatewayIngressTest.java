package com.parkio.auth.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The auth-local admin path relies on the private gateway ingress secret. */
class AdminGatewayIngressTest {

    private final GatewayAuthFilter filter = new GatewayAuthFilter("synthetic-gateway-secret", new ObjectMapper());

    @Test
    void directAdminRequestWithoutGatewaySecretIsRejectedBeforeController() throws Exception {
        assertRequest(null, false);
        assertRequest("wrong-secret", false);
        assertRequest("synthetic-gateway-secret", true);
    }

    private void assertRequest(String secret, boolean forwarded) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        if (secret != null) request.addHeader("X-Gateway-Auth", secret);
        request.addHeader("X-User-Roles", "ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedController = new AtomicBoolean();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> reachedController.set(true));
        assertThat(reachedController.get()).isEqualTo(forwarded);
        if (!forwarded) assertThat(response.getStatus()).isEqualTo(401);
        // CL-F15: only a request that passed the secret check is marked gateway-authenticated.
        assertThat(Boolean.TRUE.equals(request.getAttribute(GatewayAuthFilter.GATEWAY_AUTHENTICATED_ATTRIBUTE)))
                .isEqualTo(forwarded);
    }
}
