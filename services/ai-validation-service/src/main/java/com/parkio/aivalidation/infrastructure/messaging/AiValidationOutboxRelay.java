package com.parkio.aivalidation.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.aivalidation.domain.event.AiValidationCompletedEvent;
import com.parkio.aivalidation.infrastructure.config.KafkaTopicsConfig;
import com.parkio.aivalidation.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.aivalidation.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-process transactional-outbox relay for ai-validation-service. Polls unpublished
 * {@code outbox_events}, wraps each in the transport envelope and publishes it to
 * {@code parkio.aivalidation.result} keyed by the media id, then marks the row published
 * — only after the broker ack (ai-context/06, kafka-transport.md). At-least-once: if the
 * ack succeeds but the transaction does not commit, the row is re-sent and consumers
 * deduplicate by {@code eventId}. Rows are never deleted.
 *
 * <p>Mirrors the other relays. The advisory result is published as an event only — it is
 * never a decision (ai-context/02). Disable with {@code parkio.kafka.relay.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "parkio.kafka.relay.enabled", havingValue = "true", matchIfMissing = true)
public class AiValidationOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(AiValidationOutboxRelay.class);
    private static final int ENVELOPE_VERSION = 1;

    private final OutboxEventJpaRepository outbox;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long sendTimeoutMs;
    private final int maxAttempts;
    private final Counter publishFailedCounter;
    private final Counter deadLetteredCounter;

    public AiValidationOutboxRelay(OutboxEventJpaRepository outbox,
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
    }

    @Scheduled(fixedDelayString = "${parkio.kafka.relay.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEventEntity> batch = outbox.findUnpublishedBatchForUpdate(batchSize);
        for (OutboxEventEntity row : batch) {
            publish(row);
        }
    }

    /**
     * Publishes one row in isolation. A failure is recorded on the row (failure_count,
     * reason, timestamp) and, after {@code maxAttempts}, dead-letters it — the relay then
     * skips it so a single poison row can never block later events. Only an interrupt
     * propagates (it must abort the poll).
     */
    private void publish(OutboxEventEntity row) {
        try {
            String topic = topicFor(row.getAggregateType());
            if (topic == null) {
                // Unroutable row: deterministic poison, count it toward dead-lettering.
                recordFailure(row, "No topic mapping for aggregate type " + row.getAggregateType());
                return;
            }
            EventEnvelope envelope = toEnvelope(row);
            ProducerRecord<String, Object> record = new ProducerRecord<>(
                    topic, null, row.getAggregateId().toString(), envelope, headersFor(envelope));
            // Block on the broker ack so the row is marked published only on success.
            kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            row.markPublished();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing outbox row " + row.getId(), e);
        } catch (Exception e) {
            recordFailure(row, reasonOf(e));
        }
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

    /** ai-validation only emits advisory results. */
    static String topicFor(String aggregateType) {
        return AiValidationCompletedEvent.AGGREGATE_TYPE.equals(aggregateType)
                ? KafkaTopicsConfig.AIVALIDATION_RESULT : null;
    }

    private EventEnvelope toEnvelope(OutboxEventEntity row) {
        return new EventEnvelope(
                row.getEventId(),
                row.getEventType(),
                row.getAggregateType(),
                row.getAggregateId(),
                row.getOccurredAt(),
                ENVELOPE_VERSION,
                row.getTraceId(),
                readPayload(row));
    }

    private JsonNode readPayload(OutboxEventEntity row) {
        try {
            return objectMapper.readTree(row.getPayload());
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable outbox payload for row " + row.getId(), e);
        }
    }

    private static List<Header> headersFor(EventEnvelope e) {
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
        return headers;
    }

    private static Header header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
