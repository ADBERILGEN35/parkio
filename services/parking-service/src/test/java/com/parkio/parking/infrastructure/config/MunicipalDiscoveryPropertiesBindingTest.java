package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * DATA-WP-12: binding defaults for nearby duplicate-presentation (independent of linking).
 */
class MunicipalDiscoveryPropertiesBindingTest {
    private final ApplicationContextRunner propertiesRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void missingDuplicatePresentationPropertyResolvesTrue() {
        propertiesRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MunicipalSourceProperties.class)
                            .getDiscovery()
                            .isDuplicatePresentationEnabled())
                    .isTrue();
            assertThat(context.getBean(MunicipalSourceProperties.class)
                            .getDiscovery()
                            .getDuplicateRadiusMeters())
                    .isEqualTo(100.0);
            assertThat(context.getBean(MunicipalSourceProperties.class)
                            .getDiscovery()
                            .getOverfetchFactor())
                    .isEqualTo(2);
            assertThat(context.getBean(MunicipalSourceProperties.class)
                            .getDiscovery()
                            .getOverfetchAbsoluteMax())
                    .isEqualTo(200);
        });
    }

    @Test
    void explicitFalseDisablesDuplicatePresentation() {
        propertiesRunner
                .withPropertyValues("parkio.municipal.discovery.duplicate-presentation-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MunicipalSourceProperties.class)
                                    .getDiscovery()
                                    .isDuplicatePresentationEnabled())
                            .isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MunicipalSourceProperties.class)
    static class PropertiesConfiguration {}
}