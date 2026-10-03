package com.parkio.analytics.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.analytics.infrastructure.persistence.jpa.InboxEventJpaRepository;
import com.parkio.analytics.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The outbox now carries erasure ACKs (U05), so its dead-letter gauges must exist here too:
 * without them {@code OutboxDeadlettered} could never fire for this service (B11).
 */
class MessagingMetricsTest {

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    private final InboxEventJpaRepository inbox = mock(InboxEventJpaRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void deadLetterGaugesReflectTheRepository() {
        when(outbox.countByDeadLetteredTrue()).thenReturn(2L);
        when(outbox.countAcknowledgedDeadletters()).thenReturn(1L);
        when(outbox.countRecoveryAuditByAction("RETRY")).thenReturn(3L);
        when(outbox.countRecoveryAuditByAction("ACKNOWLEDGE")).thenReturn(1L);
        when(outbox.findOldestOpenDeadletterCreatedAt()).thenReturn(Instant.now().minusSeconds(7200));

        new MessagingMetrics(outbox, inbox, registry);

        assertThat(registry.get("parkio.outbox.deadlettered.count").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("parkio.outbox.deadlettered.acknowledged.count").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("parkio.outbox.recovery.retry.count").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("parkio.outbox.recovery.acknowledged.count").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("parkio.outbox.deadlettered.oldest.age.seconds").gauge().value()).isBetween(7199.0, 7300.0);
    }

    @Test
    void noOpenDeadLetterReportsZero() {
        when(outbox.countByDeadLetteredTrue()).thenReturn(0L);
        when(outbox.findOldestOpenDeadletterCreatedAt()).thenReturn(null);

        new MessagingMetrics(outbox, inbox, registry);

        assertThat(registry.get("parkio.outbox.deadlettered.count").gauge().value()).isZero();
        assertThat(registry.get("parkio.outbox.deadlettered.oldest.age.seconds").gauge().value()).isZero();
    }
}
