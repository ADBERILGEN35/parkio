package com.parkio.moderation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Parkio moderation-service.
 *
 * <p>Business logic is intentionally not implemented yet. The package layout
 * follows clean architecture: {@code domain}, {@code application},
 * {@code infrastructure}, {@code presentation} and {@code shared}.
 */
@SpringBootApplication
public class ModerationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModerationServiceApplication.class, args);
    }
}
