package com.parkio.media.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.media.infrastructure.storage.SingleTransmissionInterceptor;
import java.net.Proxy;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/**
 * Storage HTTP client: every timeout is positive (0 = none); a request body is transmitted at most
 * once (single-transmission guard, no redirects, no proxy); OkHttp's connection recovery stays on
 * for requests that never carried a body ({@code StorageClientSingleTransmissionTest} and
 * {@code MediaObjectWriteRetryIT} check the behaviour end to end).
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
    void clientSendsEachBodyOnceFollowsNoRedirectAndUsesNoProxy() {
        OkHttpClient client = MediaInfrastructureConfig.minioHttpClient(new MediaProperties.Storage());

        assertThat(client.interceptors()).hasAtLeastOneElementOfType(SingleTransmissionInterceptor.class);
        assertThat(client.followRedirects()).isFalse();
        assertThat(client.followSslRedirects()).isFalse();
        assertThat(client.proxy()).isEqualTo(Proxy.NO_PROXY);
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
