package com.parkio.notification.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.notification.domain.DeliveryStatus;
import com.parkio.notification.infrastructure.persistence.jpa.NotificationDeliveryAttemptJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Verifies the per-status delivery gauges read the repository counts. */
class NotificationDeliveryMetricsTest {

    @Test
    void gaugesReflectPerStatusCounts() {
        NotificationDeliveryAttemptJpaRepository attempts =
                mock(NotificationDeliveryAttemptJpaRepository.class);
        when(attempts.countByStatus(DeliveryStatus.PENDING)).thenReturn(3L);
        when(attempts.countByStatus(DeliveryStatus.SENT)).thenReturn(40L);
        when(attempts.countByStatus(DeliveryStatus.FAILED)).thenReturn(2L);
        when(attempts.countByStatus(DeliveryStatus.SKIPPED)).thenReturn(5L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new NotificationDeliveryMetrics(attempts, registry);

        assertThat(registry.get("parkio.notification.delivery.pending.count").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("parkio.notification.delivery.sent.count").gauge().value()).isEqualTo(40.0);
        assertThat(registry.get("parkio.notification.delivery.failed.count").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("parkio.notification.delivery.skipped.count").gauge().value()).isEqualTo(5.0);
    }
}
