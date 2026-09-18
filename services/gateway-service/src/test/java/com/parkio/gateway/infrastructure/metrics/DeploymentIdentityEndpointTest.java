package com.parkio.gateway.infrastructure.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * PROD-DEPLOY-01A dark acceptance reads {@code /actuator/info} to prove the runtime
 * answering on the loopback dark endpoint is the expected invite-production
 * deployment at the expected commit (see scripts/smoke-hosted-beta.sh).
 *
 * <p>That guard is only worth anything if the endpoint really serves the fields, so
 * pin the contract here: {@code deployment.environment} and {@code deployment.gitSha}
 * must be present and driven by PARKIO_ENVIRONMENT / PARKIO_GIT_SHA. Without
 * {@code management.info.env.enabled=true} Spring Boot silently omits {@code info.*}
 * properties, which would leave dark smoke unable to verify identity.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "PARKIO_ENVIRONMENT=invite-production",
        "PARKIO_GIT_SHA=866608b35702fd8c6aa4b865f5404b6da02f5efc"
})
class DeploymentIdentityEndpointTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void infoEndpointExposesNonSecretDeploymentIdentity() {
        infoClient().get().uri("/actuator/info")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deployment.environment").isEqualTo("invite-production")
                .jsonPath("$.deployment.gitSha").isEqualTo("866608b35702fd8c6aa4b865f5404b6da02f5efc");
    }

    @Test
    void infoEndpointDoesNotLeakNonInfoEnvironmentProperties() {
        // management.info.env.enabled only widens the `info.` prefix. Sensitive
        // actuator endpoints must stay unexposed regardless.
        infoClient().get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        infoClient().get().uri("/actuator/configprops").exchange().expectStatus().isNotFound();
    }

    private WebTestClient infoClient() {
        return webTestClient.mutate()
                .responseTimeout(Duration.ofSeconds(20))
                .build();
    }
}
