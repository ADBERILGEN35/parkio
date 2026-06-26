package com.parkio.media.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.media.domain.event.MediaEvent;
import com.parkio.media.infrastructure.config.KafkaTopicsConfig;
import com.parkio.media.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.media.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import com.parkio.platform.tracing.KafkaTraceContextSupport;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-process transactional-outbox relay for media-service. Polls unpublished
 * {@code outbox_events}, wraps each in the transport envelope and publishes it to
 * {@code parkio.media.media} keyed by the media id, then marks the row published — only
 * after the broker ack (ai-context/06, kafka-transport.md). At-least-once: if the ack
 * succeeds but the transaction does not commit, the row is re-sent and consumers
 * deduplicate by {@code eventId}. Rows are never deleted.
 *
 * <p>Mirrors the {@code auth}/{@code parking}/{@code gamification} relays. All media
 * events share one topic. Disable with {@code parkio.kafka.relay.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "parkio.kafka.relay.enabled", havingValue = "true", matchIfMissing = true)
public class MediaOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(MediaOutboxRelay.class);

    private final OutboxEventJpaRepository outbox;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long sendTimeoutMs;
    private final int maxAttempts;
    private final Counter publishFailedCounter;
    private final Counter deadLetteredCounter;
    private final Counter publishSuccessCounter;
    private final Timer publishTimer;
    private final DistributionSummary batchSizeSummary;

    public MediaOutboxRelay(OutboxEventJpaRepository outbox,
                            KafkaTemplate<String, Object> kafkaTemplate,
                            ObjectMapper objectMapper,
                            MeterRegistry registry,
                            @Value("${parkio.kafka.relay.batch-size:100}") int batchSize,
                            @Value("${parkio.kafka.relay.send-timeout-ms:10000}") long sendTimeoutMs,
                            @Value("${parkio.kafka.relay.max-attempts:10}") int maxAttempts) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.publishFailedCounter = Counter.builder("parkio.outbox.publish.failed")
                .description("Outbox publish attempts that failed (per-row, all causes)")
                .register(registry);
        this.deadLetteredCounter = Counter.builder("parkio.outbox.deadlettered")
                .description("Outbox rows transitioned to dead-lettered after exhausting attempts")
                .register(registry);
        this.publishSuccessCounter = Counter.builder("parkio.outbox.publish.success")
                .description("Outbox rows successfully published to Kafka (broker-acked)")
                .register(registry);
        this.publishTimer = Timer.builder("parkio.outbox.publish.duration")
                .description("Latency from relay dispatch to broker ack, per published row")
                .register(registry);
        this.batchSizeSummary = DistributionSummary.builder("parkio.outbox.batch.size")
                .description("Outbox rows claimed per relay poll")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${parkio.kafka.relay.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEventEntity> batch = outbox.findUnpublishedBatchForUpdate(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        batchSizeSummary.record(batch.size());
        // Phase 1 — dispatch every send up front so the broker round-trips pipeline instead of
        // running one at a time. Unroutable (poison) rows are failed without a send.
        List<InFlight> inFlight = new ArrayList<>(batch.size());
        for (OutboxEventEntity row : batch) {
            String topic = topicFor(row.getAggregateType());
            if (topic == null) {
                // Unroutable row: deterministic poison, count it toward dead-lettering.
                recordFailure(row, "No topic mapping for aggregate type " + row.getAggregateType());
                continue;
            }
            EventEnvelope envelope = toEnvelope(row);
            ProducerRecord<String, Object> record = new ProducerRecord<>(
                    topic, null, row.getAggregateId().toString(), envelope, headersFor(envelope, row.getTraceId()));
            Context publishContext = KafkaTraceContextSupport.extractedContext(row.getTraceId());
            try (Scope ignored = publishContext.makeCurrent()) {
                inFlight.add(new InFlight(row, System.nanoTime(), kafkaTemplate.send(record)));
            }
        }
        // Phase 2 — await the already-dispatched acks within a single shared deadline. Because
        // the sends were fired together, the whole batch settles in ~one round-trip, so the
        // transaction (and its FOR UPDATE row locks) is held only briefly — never extended
        // across batchSize serial network calls. A row is marked published only after its broker
        // ack; a failed/timed-out row stays unpublished and is retried next poll, and consumers
        // dedupe by eventId (at-least-once). A single poison row never blocks the others.
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sendTimeoutMs);
        for (InFlight f : inFlight) {
            try {
                long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
                f.future().get(remainingNanos, TimeUnit.NANOSECONDS);
                f.row().markPublished();
                publishSuccessCounter.increment();
                publishTimer.record(System.nanoTime() - f.startedNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted publishing outbox row " + f.row().getId(), e);
            } catch (Exception e) {
                recordFailure(f.row(), reasonOf(e));
            }
        }
    }

    /** A dispatched send awaiting its broker ack, paired with its row and dispatch time. */
    private record InFlight(OutboxEventEntity row, long startedNanos,
                            CompletableFuture<SendResult<String, Object>> future) {
    }

    private void recordFailure(OutboxEventEntity row, String reason) {
        publishFailedCounter.increment();
        boolean deadLettered = row.recordPublishFailure(reason, Instant.now(), maxAttempts);
        if (deadLettered) {
            deadLetteredCounter.increment();
            log.error("Outbox row {} dead-lettered after {} attempts (eventId={}, type={}): {}",
                    row.getId(), row.getFailureCount(), row.getEventId(), row.getEventType(), reason);
        } else {
            log.warn("Outbox row {} publish attempt {} failed (eventId={}, type={}): {}",
                    row.getId(), row.getFailureCount(), row.getEventId(), row.getEventType(), reason);
        }
    }

    private static String reasonOf(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /** All media events share one topic. */
    static String topicFor(String aggregateType) {
        return MediaEvent.AGGREGATE_TYPE.equals(aggregateType) ? KafkaTopicsConfig.MEDIA : null;
    }

    private EventEnvelope toEnvelope(OutboxEventEntity row) {
        return new EventEnvelope(
                row.getEventId(),
                row.getEventType(),
                row.getAggregateType(),
                row.getAggregateId(),
                row.getOccurredAt(),
                EventEnvelope.CURRENT_VERSION,
                KafkaTraceContextSupport.correlationId(row.getTraceId()),
                readPayload(row));
    }

    private JsonNode readPayload(OutboxEventEntity row) {
        try {
            return objectMapper.readTree(row.getPayload());
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable outbox payload for row " + row.getId(), e);
        }
    }

    private static List<Header> headersFor(EventEnvelope e, String storedTraceContext) {
        List<Header> headers = new ArrayList<>();
        headers.add(header("eventId", e.eventId().toString()));
        headers.add(header("eventType", e.eventType()));
        headers.add(header("aggregateType", e.aggregateType()));
        headers.add(header("aggregateId", e.aggregateId().toString()));
        headers.add(header("occurredAt", e.occurredAt().toString()));
        headers.add(header("version", Integer.toString(e.version())));
        if (e.traceId() != null) {
            headers.add(header("traceId", e.traceId()));
        }
        KafkaTraceContextSupport.addPropagationHeaders(headers, storedTraceContext);
        return headers;
    }

    private static Header header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
