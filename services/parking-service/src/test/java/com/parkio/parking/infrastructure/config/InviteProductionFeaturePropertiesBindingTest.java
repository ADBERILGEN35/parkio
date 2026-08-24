package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.recommendation.ranking.RankingProperties;
import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Protects the production env-name to Spring-property contract used by Compose. */
class InviteProductionFeaturePropertiesBindingTest {

    private final ApplicationContextRunner propertiesRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "parkio.municipal.enabled=true",
                    "parkio.municipal.izum.enabled=true",
                    "parkio.municipal.ispark.enabled=true",
                    "parkio.spa.recommendations.enabled=true",
                    "parkio.spa.ranking.enabled=true",
                    "parkio.spa.ranking.strategy=DETERMINISTIC_V1");

    @Test
    void canonicalEnvironmentNamesBindToProductionFeatureProperties() {
        propertiesRunner.run(context -> {
            assertThat(context).hasNotFailed();

            MunicipalSourceProperties municipal = context.getBean(MunicipalSourceProperties.class);
            assertThat(municipal.isEnabled()).isTrue();
            assertThat(municipal.getIzum().isEnabled()).isTrue();
            assertThat(municipal.getIspark().isEnabled()).isTrue();

            RankingProperties ranking = context.getBean(RankingProperties.class);
            assertThat(ranking.isEnabled()).isTrue();
            assertThat(ranking.getStrategy()).isEqualTo(RankingVersion.DETERMINISTIC_V1);
            assertThat(ranking.isConfigurationValid()).isTrue();

            assertThat(context.getEnvironment()
                            .getProperty("parkio.spa.recommendations.enabled", Boolean.class))
                    .isTrue();
        });
    }

    @Test
    void canonicalSystemEnvironmentNamesResolveToSpringPropertyNames() {
        SystemEnvironmentPropertySource source = new SystemEnvironmentPropertySource(
                "inviteProductionEnvironment",
                Map.of(
                        "PARKIO_MUNICIPAL_ENABLED", "true",
                        "PARKIO_MUNICIPAL_IZUM_ENABLED", "true",
                        "PARKIO_MUNICIPAL_ISPARK_ENABLED", "true",
                        "PARKIO_SPA_RECOMMENDATIONS_ENABLED", "true",
                        "PARKIO_SPA_RANKING_ENABLED", "true",
                        "PARKIO_SPA_RANKING_STRATEGY", "DETERMINISTIC_V1"));

        assertThat(source.getProperty("parkio.municipal.enabled")).isEqualTo("true");
        assertThat(source.getProperty("parkio.municipal.izum.enabled")).isEqualTo("true");
        assertThat(source.getProperty("parkio.municipal.ispark.enabled")).isEqualTo("true");
        assertThat(source.getProperty("parkio.spa.recommendations.enabled")).isEqualTo("true");
        assertThat(source.getProperty("parkio.spa.ranking.enabled")).isEqualTo("true");
        assertThat(source.getProperty("parkio.spa.ranking.strategy"))
                .isEqualTo("DETERMINISTIC_V1");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MunicipalSourceProperties.class, RankingProperties.class})
    static class PropertiesConfiguration {}
}
