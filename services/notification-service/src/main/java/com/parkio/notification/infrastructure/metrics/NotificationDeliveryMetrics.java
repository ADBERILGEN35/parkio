package com.parkio.notification.infrastructure.metrics;

import com.parkio.notification.domain.DeliveryStatus;
import com.parkio.notification.infrastructure.persistence.jpa.NotificationDeliveryAttemptJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Delivery-attempt backlog gauges exported at {@code /actuator/prometheus}:
 * {@code parkio.notification.delivery.{pending,sent,failed,skipped}.count}.
 *
 * <p>Each gauge issues one cheap COUNT query per scrape (15s locally). Alert on a
 * growing pending count (worker not draining) or a rising failed count (provider or
 * token problems). Worker outcome rates live next to the worker itself
 * ({@code parkio.notification.delivery.worker.*}).
 */
@Component
public class NotificationDeliveryMetrics {

    private final NotificationDeliveryAttemptJpaRepository attempts;

    public NotificationDeliveryMetrics(NotificationDeliveryAttemptJpaRepository attempts, MeterRegistry registry) {
        this.attempts = attempts;
        register(registry, "parkio.notification.delivery.pending.count", DeliveryStatus.PENDING,
                "Delivery attempts queued or awaiting retry");
        register(registry, "parkio.notification.delivery.sent.count", DeliveryStatus.SENT,
                "Delivery attempts successfully handed off to the provider");
        register(registry, "parkio.notification.delivery.failed.count", DeliveryStatus.FAILED,
                "Delivery attempts permanently failed after exhausting retries");
        register(registry, "parkio.notification.delivery.skipped.count", DeliveryStatus.SKIPPED,
                "Delivery attempts intentionally skipped (e.g. no active device token)");
    }

    private void register(MeterRegistry registry, String name, DeliveryStatus status, String description) {
        Gauge.builder(name, this, m -> m.attempts.countByStatus(status))
                .description(description)
                .register(registry);
    }
}
