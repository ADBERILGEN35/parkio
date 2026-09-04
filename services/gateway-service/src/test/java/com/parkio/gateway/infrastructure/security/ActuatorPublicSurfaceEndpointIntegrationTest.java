package com.parkio.gateway.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * Integration proof that disabling the public actuator surface still allows
 * loopback identity reads while sensitive management endpoints stay concealed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "parkio.gateway.public-surface.actuator-info-enabled=false",
        "PARKIO_ENVIRONMENT=invite-production",
        "PARKIO_GIT_SHA=866608b35702fd8c6aa4b865f5404b6da02f5efc"
})
class ActuatorPublicSurfaceEndpointIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void infoRemainsReadableOnLoopbackWhenPublicSurfaceDisabled() {
        client().get().uri("/actuator/info")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deployment.environment").isEqualTo("invite-production")
                .jsonPath("$.deployment.gitSha").isEqualTo("866608b35702fd8c6aa4b865f5404b6da02f5efc");
    }

    @Test
    void envAndConfigpropsRemainBlockedWhenInfoDisabled() {
        client().get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        client().get().uri("/actuator/configprops").exchange().expectStatus().isNotFound();
    }

    private WebTestClient client() {
        return webTestClient.mutate().responseTimeout(Duration.ofSeconds(20)).build();
    }
}
