package com.parkio.auth.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class EmailDeliveryConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmailDeliveryConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder);

    @Test
    void resendProviderFailsStartupWhenApiKeyMissing() {
        runner.withPropertyValues(
                        "parkio.email.provider=resend",
                        "parkio.email.from=Parkio <verify@example.com>")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void resendProviderFailsStartupWhenFromAddressMissing() {
        runner.withPropertyValues(
                        "parkio.email.provider=resend",
                        "parkio.email.resend.api-key=re_test_key")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionProfileRequiresResendProvider() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "parkio.email.provider=logging")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionProfileRejectsRawTokenLogging() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "parkio.email.provider=resend",
                        "parkio.email.resend.api-key=re_test_key",
                        "parkio.email.from=Parkio <verify@example.com>",
                        "parkio.security.email-verification.log-token=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void resendProviderStartsWhenRequiredConfigurationIsPresent() {
        runner.withPropertyValues(
                        "parkio.email.provider=resend",
                        "parkio.email.resend.api-key=re_test_key",
                        "parkio.email.from=Parkio <verify@example.com>")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
