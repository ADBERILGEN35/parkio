package com.parkio.media.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/**
 * Storage HTTP client: every timeout is positive (0 = none), and OkHttp's connection recovery stays
 * on for idempotent requests (the upload PUT is kept to one request per call by the MinIO SDK, which
 * {@code MediaDelayedObjectWriteIT} checks end to end).
 */
class MinioHttpClientConfigTest {

    @Test
    void clientKeepsConnectionRecoveryAndBoundsEveryCall() {
        OkHttpClient client = MediaInfrastructureConfig.minioHttpClient(new MediaProperties.Storage());

        // Turning recovery off for the whole client would also stop falling back to a host's other
        // address (CI: localhost resolved to ::1 first) and replacing stale pooled connections.
        assertThat(client.retryOnConnectionFailure()).isTrue();
        assertThat(client.callTimeoutMillis()).isPositive();
    }

    @Test
    void zeroOrNegativeStorageTimeoutsAreRejected() {
        MediaProperties.Storage zeroCall = new MediaProperties.Storage();
        zeroCall.setCallTimeout(Duration.ZERO);
        assertThatThrownBy(() -> MediaInfrastructureConfig.minioHttpClient(zeroCall))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("call-timeout must be positive");

        MediaProperties.Storage negativeRead = new MediaProperties.Storage();
        negativeRead.setReadTimeout(Duration.ofSeconds(-1));
        assertThatThrownBy(() -> MediaInfrastructureConfig.minioHttpClient(negativeRead))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("read-timeout must be positive");
    }
}
