package com.parkio.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Startup refusal of invite creation without a real operator token (#287 review I3). */
class InviteOperatorTokenStartupCheckTest {

    private static final String REAL_TOKEN = "k8Zq2Lr7Vw4Ny1Tp6Hd3Bf9Xs5Mc0Gj8";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InviteOperatorTokenStartupCheck.class);

    private void assertRefused(String token, String status) {
        runner.withPropertyValues("parkio.registration.invite-creation-enabled=true",
                        "parkio.registration.invite-operator-token=" + token)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    while (failure.getCause() != null) {
                        failure = failure.getCause();
                    }
                    assertThat(failure).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN (status " + status + ")");
                    if (!token.isBlank()) {
                        assertThat(failure.getMessage()).doesNotContain(token.strip());
                    }
                });
    }

    @Test
    void theInviteProductionExampleTokenWithCreationEnabledIsRefused() throws IOException {
        assertRefused(InviteOperatorTokenPolicyTest.exampleToken(Path.of("../../docker/.env.invite-production.example")),
                "PLACEHOLDER");
    }

    @Test
    void theAzureExampleTokenWithCreationEnabledIsRefused() throws IOException {
        assertRefused(InviteOperatorTokenPolicyTest.exampleToken(Path.of("../../docker/.env.azure-hosted-beta.example")),
                "PLACEHOLDER");
    }

    @Test
    void anEmptyOrShortTokenWithCreationEnabledIsRefused() {
        assertRefused("", "EMPTY");
        assertRefused("short-operator-token", "TOO_SHORT");
    }

    @Test
    void aRealTokenWithCreationEnabledStarts() {
        runner.withPropertyValues("parkio.registration.invite-creation-enabled=true",
                        "parkio.registration.invite-operator-token=" + REAL_TOKEN)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void creationDisabledDoesNotCheckTheToken() throws IOException {
        runner.withPropertyValues("parkio.registration.invite-creation-enabled=false",
                        "parkio.registration.invite-operator-token="
                                + InviteOperatorTokenPolicyTest.exampleToken(Path.of("../../docker/.env.invite-production.example")))
                .run(context -> assertThat(context).hasNotFailed());
        runner.run(context -> assertThat(context).hasNotFailed());
    }
}
