package com.parkio.auth;

import com.parkio.auth.infrastructure.recovery.RecoveryReplayLaunch;
import org.springframework.boot.SpringApplication;
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
        // The isolated restore's one-shot recovery-replay command: no web server, exits with its code.
        if (RecoveryReplayLaunch.applies(args, System.getenv(), System.getProperties())) {
            System.exit(RecoveryReplayLaunch.run(AuthServiceApplication.class, args, System.getenv(),
                    System.getProperties()));
        }
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
