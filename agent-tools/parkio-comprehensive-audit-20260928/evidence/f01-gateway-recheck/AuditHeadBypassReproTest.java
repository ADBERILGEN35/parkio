package com.parkio.gateway.infrastructure.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.application.waitlist.WaitlistApplicationService;
import com.parkio.gateway.infrastructure.config.ClientIpResolver;
import com.parkio.gateway.infrastructure.web.GatewayErrorResponseWriter;
import com.parkio.gateway.presentation.waitlist.WaitlistController;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** AUDIT REPRO (not product code): re-run of 2026-09-24 F-01 at 2877ec81. */
class AuditHeadBypassReproTest {

    @Test
    void anonymousMethodsOnExportAreRejected() {
        JwtTokenValidator validator = mock(JwtTokenValidator.class);
        WaitlistApplicationService service = mock(WaitlistApplicationService.class);
        when(service.export(any(), any())).thenReturn(Mono.just(List.of()));
        WaitlistAdminSecurityWebFilter filter = new WaitlistAdminSecurityWebFilter(
                validator, mock(SessionEpochVerifier.class), mock(AccountStatusVerifier.class),
                new GatewayErrorResponseWriter(new ObjectMapper(), Clock.systemUTC()));
        WebTestClient client = WebTestClient
                .bindToController(new WaitlistController(service, mock(ClientIpResolver.class)))
                .webFilter(filter).build();
        for (HttpMethod m : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.OPTIONS,
                HttpMethod.PUT, HttpMethod.DELETE)) {
            var r = client.method(m).uri("/api/v1/waitlist/export").exchange().expectBody().returnResult();
            System.out.println("AUDIT " + m + " status=" + r.getStatus());
        }
        var pre = client.options().uri("/api/v1/waitlist/export")
                .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET")
                .exchange().expectBody().returnResult();
        System.out.println("AUDIT PREFLIGHT status=" + pre.getStatus());
        verify(service, never()).export(any(), any());
        System.out.println("AUDIT export() never invoked: OK");
    }
}
