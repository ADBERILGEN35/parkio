package com.parkio.gateway.infrastructure.waitlist;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.gateway.application.waitlist.WaitlistHasher;
import com.parkio.gateway.application.waitlist.WaitlistProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class WaitlistEmailProviderStartupTest {

    @Test
    void loggingProviderRefusedWhenDisallowed() {
        WaitlistProperties properties = new WaitlistProperties();
        properties.setHashSecret("test-waitlist-hash-secret-32chars-min");
        properties.getEmail().setAllowLoggingProvider(false);
        LoggingWaitlistEmailSender sender =
                new LoggingWaitlistEmailSender(new WaitlistHasher(properties), properties);

        assertThatThrownBy(sender::assertLoggingAllowed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-logging-provider=false");
    }

    @Test
    void loggingProviderAllowedForLocalMock() {
        WaitlistProperties properties = new WaitlistProperties();
        properties.setHashSecret("test-waitlist-hash-secret-32chars-min");
        properties.getEmail().setAllowLoggingProvider(true);
        LoggingWaitlistEmailSender sender =
                new LoggingWaitlistEmailSender(new WaitlistHasher(properties), properties);

        assertThatCode(sender::assertLoggingAllowed).doesNotThrowAnyException();
    }

    @Test
    void resendProviderFailsClearlyWhenApiKeyMissing() {
        WaitlistProperties properties = new WaitlistProperties();
        properties.setHashSecret("test-waitlist-hash-secret-32chars-min");
        properties.getEmail().setProvider("resend");
        properties.getEmail().setFrom("Parkio <info@parkio.dev>");
        properties.getEmail().setResendApiKey("");
        properties.getEmail().setAllowLoggingProvider(false);

        ResendWaitlistEmailSender sender = new ResendWaitlistEmailSender(
                RestClient.builder(), properties, new WaitlistHasher(properties));

        assertThatThrownBy(sender::assertConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARKIO_WAITLIST_RESEND_API_KEY");
    }

    @Test
    void resendProviderAcceptsSyntheticKeyAndFrom() {
        WaitlistProperties properties = new WaitlistProperties();
        properties.setHashSecret("test-waitlist-hash-secret-32chars-min");
        properties.getEmail().setProvider("resend");
        properties.getEmail().setFrom("Parkio <info@parkio.dev>");
        properties.getEmail().setResendApiKey("re_synthetic_test_key_not_real");
        properties.getEmail().setAllowLoggingProvider(false);

        ResendWaitlistEmailSender sender = new ResendWaitlistEmailSender(
                RestClient.builder(), properties, new WaitlistHasher(properties));

        assertThatCode(sender::assertConfigured).doesNotThrowAnyException();
    }
}
