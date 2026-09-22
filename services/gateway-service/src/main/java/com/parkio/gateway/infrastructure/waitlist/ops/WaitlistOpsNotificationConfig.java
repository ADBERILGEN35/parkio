package com.parkio.gateway.infrastructure.waitlist.ops;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Exporter + scheduler exist only when explicitly enabled. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "parkio.waitlist.ops-notifications", name = "enabled", havingValue = "true")
public class WaitlistOpsNotificationConfig {

    private static final Logger log = LoggerFactory.getLogger(WaitlistOpsNotificationConfig.class);

    @Bean
    WaitlistOpsNotificationExporter waitlistOpsNotificationExporter(
            JdbcWaitlistOpsNotificationOutbox outbox,
            WaitlistOpsNotificationProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        if (!outbox.isActive()) {
            log.warn("Waitlist ops notifications enabled without export-dir; nothing will be recorded.");
        }
        return new WaitlistOpsNotificationExporter(outbox, properties, clock, meterRegistry);
    }
}
