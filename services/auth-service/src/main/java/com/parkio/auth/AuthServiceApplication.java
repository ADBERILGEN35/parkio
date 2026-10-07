package com.parkio.auth;

import com.parkio.auth.infrastructure.recovery.RecoveryReplayLaunch;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Properties;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Parkio auth-service.
 *
 * <p>Business logic is intentionally not implemented yet. The package layout
 * follows clean architecture: {@code domain}, {@code application},
 * {@code infrastructure}, {@code presentation} and {@code shared}.
 */
@SpringBootApplication
public class AuthServiceApplication {

    public static void main(String[] args) {
        OptionalInt recovery = recoveryExit(args, System.getenv(), System.getProperties());
        if (recovery.isPresent()) {
            System.exit(recovery.getAsInt());
        }
        OptionalInt refused = RecoveryReplayLaunch.startOrdinary(AuthServiceApplication.class, args);
        if (refused.isPresent()) {
            System.exit(refused.getAsInt());
        }
    }

    /**
     * The isolated restore's one-shot recovery-replay command, when {@code args} or the environment
     * ask for it: its exit code (no web server; it never starts the service). Empty for an
     * ordinary start.
     */
    static OptionalInt recoveryExit(String[] args, Map<String, String> env, Properties system) {
        if (!RecoveryReplayLaunch.applies(args, env, system)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(RecoveryReplayLaunch.run(AuthServiceApplication.class, args, env, system));
    }
}
