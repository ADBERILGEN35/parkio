package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * CL-F22 (owner option C, #250 review N1): the unchanged-feed alert threshold of İZUM and İSPARK.
 * The default is 4h, and 0 is the only way to disable the alert. A negative value refuses startup
 * with an error that names the property, instead of silently disabling the alert.
 */
class MunicipalUnchangedFeedThresholdBindingTest {
    private final ApplicationContextRunner propertiesRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark"})
    void defaultsToFourHours(String source) {
        propertiesRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(threshold(context.getBean(MunicipalSourceProperties.class), source))
                    .isEqualTo(Duration.ofHours(4));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark"})
    void zeroDisablesTheAlert(String source) {
        propertiesRunner
                .withPropertyValues("parkio.municipal." + source + ".unchanged-feed-alert-after=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(threshold(context.getBean(MunicipalSourceProperties.class), source))
                            .isZero();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark"})
    void aNegativeThresholdRefusesStartupAndNamesTheProperty(String source) {
        propertiesRunner
                .withPropertyValues("parkio.municipal." + source + ".unchanged-feed-alert-after=-4h")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("parkio.municipal." + source
                                    + ".unchanged-feed-alert-after must be 0 (alert disabled) or a positive duration,"
                                    + " was PT-4H");
                });
    }

    private static Duration threshold(MunicipalSourceProperties properties, String source) {
        return "izum".equals(source)
                ? properties.getIzum().getUnchangedFeedAlertAfter()
                : properties.getIspark().getUnchangedFeedAlertAfter();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MunicipalSourceProperties.class)
    static class PropertiesConfiguration {}
}
