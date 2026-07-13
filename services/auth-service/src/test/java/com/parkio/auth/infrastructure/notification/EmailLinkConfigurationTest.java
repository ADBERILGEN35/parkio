package com.parkio.auth.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * Proves the placeholder chain in the shipped application.yml: the canonical
 * {@code PARKIO_WEB_BASE_URL} drives every user-facing email link, the explicit
 * per-link env vars still win when set, and local dev keeps the Vite dev-server
 * default. This guards the exact configuration the hosted-beta deployment relies
 * on, not a test-only copy of it.
 */
class EmailLinkConfigurationTest {

    private static final String VERIFICATION_URL = "parkio.security.email-verification.url";
    private static final String RESET_URL = "parkio.security.password-reset.url";

    private static StandardEnvironment environmentWith(Map<String, Object> vars) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", vars));
        new YamlPropertySourceLoader()
                .load("main-application", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }

    @Test
    void defaultsToLocalViteDevServerForLocalDevelopment() throws IOException {
        StandardEnvironment env = environmentWith(Map.of());

        assertThat(env.getProperty(VERIFICATION_URL)).isEqualTo("http://localhost:5173/verify-email");
        assertThat(env.getProperty(RESET_URL)).isEqualTo("http://localhost:5173/reset-password");
    }

    @Test
    void webBaseUrlDrivesVerificationAndResetLinks() throws IOException {
        StandardEnvironment env = environmentWith(Map.of(
                "PARKIO_WEB_BASE_URL", "https://app.parkio.dev"));

        assertThat(env.getProperty(VERIFICATION_URL)).isEqualTo("https://app.parkio.dev/verify-email");
        assertThat(env.getProperty(RESET_URL)).isEqualTo("https://app.parkio.dev/reset-password");
    }

    @Test
    void explicitPerLinkOverridesStillWin() throws IOException {
        StandardEnvironment env = environmentWith(Map.of(
                "PARKIO_WEB_BASE_URL", "https://app.parkio.dev",
                "PARKIO_EMAIL_VERIFICATION_URL", "https://beta.parkio.dev/custom-verify",
                "PARKIO_PASSWORD_RESET_URL", "https://beta.parkio.dev/custom-reset"));

        assertThat(env.getProperty(VERIFICATION_URL)).isEqualTo("https://beta.parkio.dev/custom-verify");
        assertThat(env.getProperty(RESET_URL)).isEqualTo("https://beta.parkio.dev/custom-reset");
    }
}
