package com.parkio.parking.infrastructure.config;

import com.parkio.parking.application.ParkingSearchSettings;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Infrastructure wiring: a system-UTC {@link Clock}, the application's
 * {@link ParkingSearchSettings} derived from properties (so the application layer
 * stays free of Spring config types), and scheduling for the outbox relay poller.
 */
@Configuration
@EnableConfigurationProperties({ParkingProperties.class, GeocodingProperties.class})
@EnableScheduling
public class ParkingInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ParkingSearchSettings parkingSearchSettings(ParkingProperties properties) {
        return new ParkingSearchSettings(
                properties.getSearch().getDefaultRadiusMeters(),
                properties.getSearch().getDefaultResultLimit(),
                properties.getSearch().getMaxRadiusMeters(),
                properties.getSearch().getMaxResultLimit());
    }
}
