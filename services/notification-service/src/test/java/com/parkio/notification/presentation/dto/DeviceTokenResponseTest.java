package com.parkio.notification.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Guards that the raw device-token value never appears in the API response (ai-context/07). */
class DeviceTokenResponseTest {

    @Test
    void doesNotExposeRawTokenValue() {
        String rawToken = "super-secret-device-token-value";
        DeviceToken token = DeviceToken.register(UUID.randomUUID(), rawToken, DevicePlatform.ANDROID,
                Instant.parse("2026-06-07T12:00:00Z"));

        DeviceTokenResponse response = DeviceTokenResponse.from(token);

        assertThat(response.toString()).doesNotContain(rawToken);
        assertThat(response.id()).isEqualTo(token.id());
        assertThat(response.platform()).isEqualTo("ANDROID");
        assertThat(response.active()).isTrue();
    }
}
