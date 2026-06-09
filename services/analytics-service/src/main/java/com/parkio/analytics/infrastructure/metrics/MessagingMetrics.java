package com.parkio.analytics.infrastructure.metrics;

import com.parkio.analytics.infrastructure.persistence.jpa.InboxEventJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Inbox gauge exported at {@code /actuator/prometheus} (analytics-service is a pure
 * consumer — it has no outbox, so no outbox gauges).
 *
 * <p>The gauge issues one cheap COUNT query per scrape (15s locally), never on the
 * request path.
 */
@Component
public class MessagingMetrics {

    private final InboxEventJpaRepository inbox;

    public MessagingMetrics(InboxEventJpaRepository inbox, MeterRegistry registry) {
        this.inbox = inbox;
        Gauge.builder("parkio.inbox.processed.count", this, m -> m.inbox.count())
                .description("Processed inbox rows currently retained for consumer dedup")
                .register(registry);
    }
}
