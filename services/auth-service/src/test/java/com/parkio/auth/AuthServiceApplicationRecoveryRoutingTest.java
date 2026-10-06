package com.parkio.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * {@code main}'s decision (PR #295 review N4): an ordinary start never reaches the recovery-replay
 * launcher, and a recovery request never starts the service. The launch itself is covered end to
 * end by {@code RecoveryReplayLaunchPostgresIT}, which runs {@code main} in its own JVM.
 */
class AuthServiceApplicationRecoveryRoutingTest {

    private static final String[] RECOVERY_OPTIONS = {
        "--evidence=/tmp/set.json", "--trust=/tmp/trust.json", "--attempt=5e5e5e5e-0000-4000-8000-00000000a771",
        "--dataset=stamp-1", "--target-identity=postgresql:7000000000000000099:parkio_auth",
        "--verdict-out=/tmp/verdict.json",
    };

    @Test
    void anOrdinaryStartIsNotARecovery() {
        assertThat(AuthServiceApplication.recoveryExit(new String[] {"--server.port=8081"}, Map.of(), new Properties()))
                .isEmpty();
        assertThat(AuthServiceApplication.recoveryExit(new String[0], Map.of("SPRING_PROFILES_ACTIVE", "prod"),
                new Properties())).isEmpty();
    }

    @Test
    void recoveryOptionsWithoutTheProfileExitRefusedWithoutStartingTheService() {
        assertThat(AuthServiceApplication.recoveryExit(RECOVERY_OPTIONS, Map.of(), new Properties())).hasValue(20);
    }

    @Test
    void theProfileWithASpringOptionExitsRefusedWithoutStartingTheService() {
        String[] args = {"--spring.main.web-application-type=servlet"};

        assertThat(AuthServiceApplication.recoveryExit(args, Map.of("SPRING_PROFILES_ACTIVE", "recovery-replay"),
                new Properties())).hasValue(20);
    }
}
