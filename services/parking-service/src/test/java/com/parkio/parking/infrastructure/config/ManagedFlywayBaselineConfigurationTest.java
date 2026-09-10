package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.infrastructure.persistence.ManagedFlywayBaselineStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * PROD-DEPLOY-01A-R8.5 — the explicit baseline must be armed on the managed profile and nowhere
 * else.
 *
 * <p>An accidentally-armed strategy would baseline a fresh local or hosted-beta database, skipping
 * {@code V1__enable_postgis.sql} on an environment that is perfectly able to run it — leaving the
 * extension uninstalled and V2 failing on its geography column. The default must therefore be off,
 * and only {@code docker/docker-compose.managed-db.yml} turns it on.
 */
class ManagedFlywayBaselineConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ManagedFlywayBaselineConfiguration.class);

    @Test
    void strategyIsAbsentByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(FlywayMigrationStrategy.class));
    }

    @Test
    void strategyIsAbsentWhenExplicitlyDisabled() {
        runner.withPropertyValues("parkio.parking.flyway.managed-baseline-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FlywayMigrationStrategy.class));
    }

    @Test
    void strategyIsArmedOnlyWhenTheManagedProfileEnablesIt() {
        runner.withPropertyValues("parkio.parking.flyway.managed-baseline-enabled=true")
                .run(context -> assertThat(context)
                        .getBean(FlywayMigrationStrategy.class)
                        .isInstanceOf(ManagedFlywayBaselineStrategy.class));
    }

    /**
     * The production {@code application.yml} must declare the placeholder that turns
     * {@code docker/docker-compose.managed-db.yml}'s environment variable into the property the
     * condition above reads.
     *
     * <p>The two are not interchangeable. Spring maps an environment variable by turning
     * underscores into dots, so {@code PARKIO_PARKING_FLYWAY_MANAGED_BASELINE_ENABLED} reaches
     * {@code parkio.parking.flyway.managed-baseline-enabled} only because the YAML declares that
     * placeholder explicitly. Lose the declaration — a second {@code parking:} key under
     * {@code parkio:} is enough, since YAML keeps only the last one — and the managed profile
     * silently stops arming the baseline while every other test here still passes.
     *
     * <p>Read from the file rather than the classpath on purpose: {@code src/test/resources}
     * carries its own {@code application.yml}, which shadows the production one in every test JVM.
     */
    @Test
    void productionApplicationYmlDeclaresTheManagedBaselinePlaceholder() {
        Path applicationYml = Path.of("src/main/resources/application.yml");
        assertThat(Files.exists(applicationYml))
                .as("run from the parking-service project directory")
                .isTrue();

        List<PropertySource<?>> sources = loadYaml(applicationYml);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getProperty("parkio.parking.flyway.managed-baseline-enabled"))
                .isEqualTo("${PARKIO_PARKING_FLYWAY_MANAGED_BASELINE_ENABLED:false}");
        assertThat(sources.get(0).getProperty("spring.flyway.baseline-version"))
                .as("the baseline identity the managed strategy writes")
                .isEqualTo("${SPRING_FLYWAY_BASELINE_VERSION:1}");
    }

    private static List<PropertySource<?>> loadYaml(Path path) {
        try {
            return new YamlPropertySourceLoader().load("application", new FileSystemResource(path));
        } catch (Exception ex) {
            throw new IllegalStateException("could not parse " + path, ex);
        }
    }
}
