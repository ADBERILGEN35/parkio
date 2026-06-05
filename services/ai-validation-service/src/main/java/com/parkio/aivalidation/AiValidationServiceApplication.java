package com.parkio.aivalidation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Parkio ai-validation-service.
 *
 * <p>Business logic is intentionally not implemented yet. The package layout
 * follows clean architecture: {@code domain}, {@code application},
 * {@code infrastructure}, {@code presentation} and {@code shared}.
 */
@SpringBootApplication
public class AiValidationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiValidationServiceApplication.class, args);
    }
}
