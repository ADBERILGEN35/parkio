package com.parkio.auth.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.auth.application.LoginFailureTracker;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** CL-F15: the client header is trusted only on gateway-authenticated requests, and only as an IP literal. */
class ClientIdentityResolverTest {

    private final ClientIdentityResolver resolver = new ClientIdentityResolver();

    @Test
    void ignoresTheHeaderWhenTheRequestWasNotGatewayAuthenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader(ClientIdentityResolver.CLIENT_IP_HEADER, "198.51.100.7");

        assertThat(resolver.clientKey(request)).isEqualTo(LoginFailureTracker.UNKNOWN_CLIENT);
    }

    @Test
    void usesTheGatewayResolvedAddressOnAnAuthenticatedRequest() {
        MockHttpServletRequest request = authenticated();
        request.addHeader(ClientIdentityResolver.CLIENT_IP_HEADER, " 198.51.100.7 ");
        assertThat(resolver.clientKey(request)).isEqualTo("198.51.100.7");

        // IPv6 is keyed by its /64 network (CL-F15 v2): rotating addresses inside it is one client.
        MockHttpServletRequest v6 = authenticated();
        v6.addHeader(ClientIdentityResolver.CLIENT_IP_HEADER, "2001:DB8::1");
        assertThat(resolver.clientKey(v6)).isEqualTo("2001:db8:0:0:0:0:0:0/64");
        MockHttpServletRequest rotated = authenticated();
        rotated.addHeader(ClientIdentityResolver.CLIENT_IP_HEADER, "2001:db8::dead:beef");
        assertThat(resolver.clientKey(rotated)).isEqualTo(resolver.clientKey(v6));
    }

    @Test
    void fallsBackToTheUnknownBucketWithoutOrWithAMalformedHeader() {
        assertThat(resolver.clientKey(authenticated())).isEqualTo(LoginFailureTracker.UNKNOWN_CLIENT);
        for (String bad : new String[] {"", "evil.example.com", "198.51.100.7, 203.0.113.9", "[2001:db8::1]",
                "fe80::1%eth0", "a".repeat(46), "abc", "1.2.3"}) {
            MockHttpServletRequest request = authenticated();
            request.addHeader(ClientIdentityResolver.CLIENT_IP_HEADER, bad);
            assertThat(resolver.clientKey(request)).as(bad).isEqualTo(LoginFailureTracker.UNKNOWN_CLIENT);
        }
    }

    private static MockHttpServletRequest authenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setAttribute(GatewayAuthFilter.GATEWAY_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
        return request;
    }
}
