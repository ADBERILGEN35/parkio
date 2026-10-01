package com.parkio.media.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/** The storage HTTP client never retries a request on its own, and every timeout is positive (0 = none). */
class MinioHttpClientConfigTest {

    @Test
    void clientNeverRetriesARequestOnItsOwn() {
        OkHttpClient client = MediaInfrastructureConfig.minioHttpClient(new MediaProperties.Storage());

        assertThat(client.retryOnConnectionFailure()).isFalse();
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
