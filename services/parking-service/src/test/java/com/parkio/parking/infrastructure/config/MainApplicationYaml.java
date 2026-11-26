package com.parkio.parking.infrastructure.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * The production {@code application.yml}, read from disk on purpose: {@code src/test/resources} carries
 * its own {@code application.yml}, which shadows the production one in every test JVM.
 */
final class MainApplicationYaml {

    static final Path FILE = IzumReadTimeoutConfigTest.ROOT.resolve("src/main/resources/application.yml");

    private MainApplicationYaml() {
    }

    static List<PropertySource<?>> propertySources() {
        try {
            return new YamlPropertySourceLoader().load("application", new FileSystemResource(FILE));
        } catch (Exception ex) {
            throw new IllegalStateException("could not parse " + FILE, ex);
        }
    }

    /**
     * Binds {@code prefix} from application.yml with {@code environment} as the environment variables on
     * top. The test JVM's own environment and system properties are left out. The binding does not
     * validate. As in the container, a variable such as {@code PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT}
     * reaches {@code parkio.municipal.konya.read-timeout} whether or not application.yml sets it through
     * a {@code ${...}} placeholder.
     */
    static <T> T bind(String prefix, Class<T> type, Map<String, Object> environment) {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource("environment", environment));
        propertySources().forEach(sources::addLast);
        return Binder.get(env).bind(prefix, type).get();
    }
}
