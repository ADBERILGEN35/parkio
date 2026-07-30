package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.LinkCandidateGenerationOrchestrator;
import com.parkio.parking.application.LinkReviewApplicationService;
import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
import com.parkio.parking.presentation.RegistryLinkCandidateGenerationController;
import com.parkio.parking.presentation.RegistryLinkReviewController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class RegistryPropertiesBindingTest {
    private final ApplicationContextRunner propertiesRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void missingAutomaticLinkingPropertyResolvesFalse() {
        propertiesRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RegistryProperties.class).isAutomaticLinkingEnabled()).isFalse();
        });
    }

    @Test
    void automaticLinkingTrueFailsContextStartup() {
        propertiesRunner
                .withPropertyValues("parkio.municipal.registry.automatic-linking-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("Automatic municipal registry linking is prohibited");
                });
    }

    @Test
    void reviewControllerBeanIsAbsentWhenReviewApiDisabled() {
        new ApplicationContextRunner()
                .withBean(LinkReviewApplicationService.class, () -> mock(LinkReviewApplicationService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(ReviewControllerConfiguration.class)
                .withPropertyValues("parkio.municipal.registry.review-api-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RegistryLinkReviewController.class));
        // The edge/gateway therefore observes no mapped controller route (404), not an enabled-but-forbidden API.
    }

    @Test
    void generationControllerBeanIsAbsentWhenCandidateGenerationDisabled() {
        new ApplicationContextRunner()
                .withBean(LinkCandidateGenerationOrchestrator.class,
                        () -> mock(LinkCandidateGenerationOrchestrator.class))
                .withBean(LinkCandidateGenerationRunPort.class,
                        () -> mock(LinkCandidateGenerationRunPort.class))
                .withUserConfiguration(GenerationControllerConfiguration.class)
                .withPropertyValues("parkio.municipal.registry.candidate-generation-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(RegistryLinkCandidateGenerationController.class));
        // A disabled controller contributes no MVC mapping, so the edge observes 404.
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RegistryProperties.class)
    static class PropertiesConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @Import(RegistryLinkReviewController.class)
    static class ReviewControllerConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @Import(RegistryLinkCandidateGenerationController.class)
    static class GenerationControllerConfiguration {}
}