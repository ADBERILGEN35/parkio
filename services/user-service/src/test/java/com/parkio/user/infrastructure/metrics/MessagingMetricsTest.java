package com.parkio.user.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.user.infrastructure.persistence.jpa.InboxEventJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    @Test
    void gaugesReflectRepositoryCounts() {
        when(outbox.countByPublishedFalse()).thenReturn(4L);
        when(outbox.findOldestUnpublishedCreatedAt()).thenReturn(NOW.minusSeconds(90));
        when(inbox.count()).thenReturn(12L);

        new MessagingMetrics(outbox, inbox, clock, registry);

        assertThat(registry.get("parkio.outbox.unpublished.count").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("parkio.outbox.oldest.unpublished.age.seconds").gauge().value()).isEqualTo(90.0);
        assertThat(registry.get("parkio.inbox.processed.count").gauge().value()).isEqualTo(12.0);
    }

    @Test
    void emptyBacklogReportsZeroAge() {
        when(outbox.countByPublishedFalse()).thenReturn(0L);
        when(outbox.findOldestUnpublishedCreatedAt()).thenReturn(null);

        new MessagingMetrics(outbox, inbox, clock, registry);

        assertThat(registry.get("parkio.outbox.unpublished.count").gauge().value()).isZero();
        assertThat(registry.get("parkio.outbox.oldest.unpublished.age.seconds").gauge().value()).isZero();
    }
}
