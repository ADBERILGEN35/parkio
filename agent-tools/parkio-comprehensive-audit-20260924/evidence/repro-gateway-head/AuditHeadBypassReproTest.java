package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

/** AUDIT REPRO (not product code): anonymous HEAD reaches the admin export handler. */
class AuditHeadBypassReproTest {

    @Test
    void anonymousHeadOnExportReachesHandler() {
        JwtTokenValidator validator = mock(JwtTokenValidator.class);
        WaitlistApplicationService service = mock(WaitlistApplicationService.class);
        when(service.export(any(), any())).thenReturn(Mono.just(List.of()));
        WaitlistAdminSecurityWebFilter filter = new WaitlistAdminSecurityWebFilter(
                validator, new GatewayErrorResponseWriter(new ObjectMapper(), Clock.systemUTC()));
        WebTestClient client = WebTestClient
                .bindToController(new WaitlistController(service, mock(ClientIpResolver.class)))
                .webFilter(filter)
                .build();

        var get = client.get().uri("/api/v1/waitlist/export").exchange().expectBody().returnResult();
        System.out.println("AUDIT GET  status=" + get.getStatus());
        assertThat(get.getStatus().value()).isEqualTo(401);

        var head = client.method(HttpMethod.HEAD).uri("/api/v1/waitlist/export").exchange()
                .expectBody().returnResult();
        System.out.println("AUDIT HEAD status=" + head.getStatus()
                + " content-type=" + head.getResponseHeaders().getContentType()
                + " content-disposition=" + head.getResponseHeaders().getFirst("Content-Disposition"));
        verify(service).export(any(), any());
        verifyNoInteractions(validator);
    }
}
