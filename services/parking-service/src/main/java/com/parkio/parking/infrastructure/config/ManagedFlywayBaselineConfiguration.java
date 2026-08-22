package com.parkio.parking.infrastructure.config;

import com.parkio.parking.infrastructure.persistence.ManagedFlywayBaselineStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PROD-DEPLOY-01A-R8.5 — arms the explicit managed-DB Flyway baseline, and only there.
 *
 * <p>Gated on {@code parkio.parking.flyway.managed-baseline-enabled}, which defaults to false and
 * is set only by {@code docker/docker-compose.managed-db.yml}. Local, CI and hosted-beta therefore
 * keep the stock Spring Boot migration strategy, where {@code V1__enable_postgis.sql} executes
 * normally and records its checksum — the behaviour {@code ManagedParkingFlywayBaselineIT}'s
 * STATE E pins, and the reason this fix must never baseline a non-managed database.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "parkio.parking.flyway.managed-baseline-enabled", havingValue = "true")
public class ManagedFlywayBaselineConfiguration {

    @Bean
    FlywayMigrationStrategy managedFlywayBaselineStrategy() {
        return new ManagedFlywayBaselineStrategy();
    }
}
