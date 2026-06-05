package com.parkio.parking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Parkio parking-service.
 *
 * <p>Business logic is intentionally not implemented yet. The package layout
 * follows clean architecture: {@code domain}, {@code application},
 * {@code infrastructure}, {@code presentation} and {@code shared}.
 */
@SpringBootApplication
public class ParkingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParkingServiceApplication.class, args);
    }
}
