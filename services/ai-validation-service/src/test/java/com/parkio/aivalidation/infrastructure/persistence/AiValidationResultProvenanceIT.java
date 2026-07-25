package com.parkio.aivalidation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
import com.parkio.aivalidation.domain.DecisionSource;
import com.parkio.aivalidation.domain.ModerationProvenance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies the additive V10 provenance columns against a real PostgreSQL instance:
 * Flyway applies V10, Hibernate {@code validate} confirms the entity matches the schema,
 * and a result saved with full {@link ModerationProvenance} round-trips intact.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class AiValidationResultProvenanceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_aivalidation_prov_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("parkio.gateway.internal-secret",
                () -> "test-only-parkio-gateway-internal-secret-0123456789");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("management.tracing.enabled", () -> "false");
    }

    @Autowired
    private AiValidationResultRepository results;

    @Test
    void provenanceRoundTripsThroughV10Columns() {
        UUID mediaId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-25T09:00:00Z");
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String identity = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
        ModerationProvenance provenance = new ModerationProvenance(
                DecisionSource.AUTOMATED, "gemini", "gemini-2.5-flash-lite", "gemini-2.5-flash-lite",
                "2026-07-region-first-v1", "2026-07-v1", "acc0.70-rej0.70",
                hash, 0.91, identity, "v1");
        AiValidationResult result = new AiValidationResult(
                UUID.randomUUID(), mediaId, UUID.randomUUID(), UUID.randomUUID(),
                AiValidationStatus.PASSED, 80, 10, 85, 88, List.of(), List.of(), provenance, now, null);

        results.save(result);

        List<AiValidationResult> reloaded = results.findByMediaId(mediaId);
        assertThat(reloaded).hasSize(1);
        ModerationProvenance rt = reloaded.get(0).provenance();
        assertThat(rt.decisionSource()).isEqualTo(DecisionSource.AUTOMATED);
        assertThat(rt.provider()).isEqualTo("gemini");
        assertThat(rt.modelId()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(rt.modelVersion()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(rt.promptVersion()).isEqualTo("2026-07-region-first-v1");
        assertThat(rt.policyVersion()).isEqualTo("2026-07-v1");
        assertThat(rt.thresholdVersion()).isEqualTo("acc0.70-rej0.70");
        assertThat(rt.canonicalImageHash()).isEqualTo(hash);
        assertThat(rt.rawConfidence()).isEqualTo(0.91);
        assertThat(rt.requestIdentity()).isEqualTo(identity);
        assertThat(rt.requestIdentityVersion()).isEqualTo("v1");
        assertThat(rt.hasCompleteVersionTuple()).isTrue();
    }

    @Test
    void legacyNullProvenanceRoundTripsAsNone() {
        UUID mediaId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-25T09:05:00Z");
        AiValidationResult result = new AiValidationResult(
                UUID.randomUUID(), mediaId, null, null,
                AiValidationStatus.WARNING, 50, 20, 60, 40, List.of(), List.of(),
                ModerationProvenance.none(), now, null);

        results.save(result);

        ModerationProvenance rt = results.findByMediaId(mediaId).get(0).provenance();
        assertThat(rt.decisionSource()).isNull();
        assertThat(rt.canonicalImageHash()).isNull();
        assertThat(rt.hasCompleteVersionTuple()).isFalse();
    }
}
