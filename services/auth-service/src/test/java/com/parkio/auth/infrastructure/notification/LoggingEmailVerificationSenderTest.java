package com.parkio.auth.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LoggingEmailVerificationSenderTest {

    private static final String TOKEN = "raw-verification-token";

    @Test
    void doesNotLogRawTokenWhenDisabled(CapturedOutput output) {
        LoggingEmailVerificationSender sender = new LoggingEmailVerificationSender(
                "https://app.example.com/verify-email", false, new EmailDeliveryMetrics(new SimpleMeterRegistry()));

        sender.sendVerificationLink("user@example.com", TOKEN);

        assertThat(output).doesNotContain(TOKEN);
        assertThat(output).contains("emailHash=");
    }
}