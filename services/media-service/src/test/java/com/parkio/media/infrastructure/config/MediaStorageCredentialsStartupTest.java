package com.parkio.media.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.media.MediaServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * CL-F38d: media-service must not start with the well-known MinIO default credentials
 * (minioadmin) or with none at all, except under the local dev profile. Uses the H2 test
 * configuration; only the storage credentials and profile vary.
 */
class MediaStorageCredentialsStartupTest {

    @Test
    void defaultMinioCredentialsStopStartupOutsideTheDevProfile() {
        assertThatThrownBy(() -> start(null, "minioadmin", "minioadmin").close())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("minioadmin")
                .hasMessageContaining("dev profile");
    }

    @Test
    void missingCredentialsStopStartup() {
        assertThatThrownBy(() -> start(null, "", "").close())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("PARKIO_MEDIA_STORAGE_ACCESS_KEY");
    }

    @Test
    void theDevProfileKeepsTheLocalDefaults() {
        try (ConfigurableApplicationContext context = start("dev", "minioadmin", "minioadmin")) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    private static ConfigurableApplicationContext start(String profile, String accessKey, String secretKey) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(MediaServiceApplication.class);
        if (profile != null) {
            builder.profiles(profile);
        }
        // Command-line arguments: builder default properties would lose to application.yml.
        return builder.run(
                "--server.port=0",
                "--parkio.media.storage.access-key=" + accessKey,
                "--parkio.media.storage.secret-key=" + secretKey);
    }
}
