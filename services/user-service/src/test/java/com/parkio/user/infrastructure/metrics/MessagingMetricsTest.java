package com.parkio.user.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.user.infrastructure.persistence.jpa.InboxEventJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.ref.WeakReference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Verifies the outbox/inbox backlog gauges read repository counts lazily and convert
 * the oldest unpublished row into an age in seconds.
 */
class MessagingMetricsTest {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    private final InboxEventJpaRepository inbox = mock(InboxEventJpaRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    // Micrometer gauges hold their state object weakly. The service's Spring context keeps the
    // metrics bean alive; a test that discards the instance lets the collector take it before the
    // gauges are read, and they then report NaN. Keep it here for the whole test.
    private MessagingMetrics metrics;

    @Test
    void gaugesReflectRepositoryCounts() {
        when(outbox.countByPublishedFalseAndDeadLetteredFalse()).thenReturn(4L);
        when(outbox.countByDeadLetteredTrue()).thenReturn(2L);
        when(outbox.findOldestUnpublishedCreatedAt()).thenReturn(NOW.minusSeconds(90));
        when(inbox.count()).thenReturn(12L);

        metrics = new MessagingMetrics(outbox, inbox, clock, registry);
        collectGarbage();

        assertThat(registry.get("parkio.outbox.unpublished.count").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("parkio.outbox.deadlettered.count").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("parkio.outbox.oldest.unpublished.age.seconds").gauge().value()).isEqualTo(90.0);
        assertThat(registry.get("parkio.inbox.processed.count").gauge().value()).isEqualTo(12.0);
    }

    @Test
    void emptyBacklogReportsZeroAge() {
        when(outbox.countByPublishedFalseAndDeadLetteredFalse()).thenReturn(0L);
        when(outbox.countByDeadLetteredTrue()).thenReturn(0L);
        when(outbox.findOldestUnpublishedCreatedAt()).thenReturn(null);

        metrics = new MessagingMetrics(outbox, inbox, clock, registry);
        collectGarbage();

        assertThat(registry.get("parkio.outbox.unpublished.count").gauge().value()).isZero();
        assertThat(registry.get("parkio.outbox.deadlettered.count").gauge().value()).isZero();
        assertThat(registry.get("parkio.outbox.oldest.unpublished.age.seconds").gauge().value()).isZero();
    }

    /**
     * Runs the collector until a fresh, weakly reachable object is gone. A gauge whose state object
     * were only weakly reachable would read NaN after this.
     */
    private static void collectGarbage() {
        WeakReference<Object> canary = new WeakReference<>(new Object());
        for (int attempt = 0; attempt < 50 && canary.get() != null; attempt++) {
            System.gc();
        }
        assertThat(canary.get()).as("a garbage collection ran").isNull();
    }
}
