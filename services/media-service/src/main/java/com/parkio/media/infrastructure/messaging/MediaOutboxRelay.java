package com.parkio.media.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.media.domain.event.MediaEvent;
import com.parkio.media.infrastructure.config.KafkaTopicsConfig;
import com.parkio.media.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.media.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.nio.charset.StandardCharsets;
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

    public MediaOutboxRelay(OutboxEventJpaRepository outbox,
                            KafkaTemplate<String, Object> kafkaTemplate,
                            ObjectMapper objectMapper,
                            @Value("${parkio.kafka.relay.batch-size:100}") int batchSize,
                            @Value("${parkio.kafka.relay.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${parkio.kafka.relay.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEventEntity> batch = outbox.findUnpublishedBatchForUpdate(batchSize);
        for (OutboxEventEntity row : batch) {
            publish(row);
        }
    }

    private void publish(OutboxEventEntity row) {
        String topic = topicFor(row.getAggregateType());
        if (topic == null) {
            log.warn("No topic mapping for outbox aggregate type {} (row {}); leaving unpublished",
                    row.getAggregateType(), row.getId());
            return;
        }
        EventEnvelope envelope = toEnvelope(row);
        ProducerRecord<String, Object> record = new ProducerRecord<>(
                topic, null, row.getAggregateId().toString(), envelope, headersFor(envelope));
        try {
            // Block on the broker ack so the row is marked published only on success.
            kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing outbox row " + row.getId(), e);
        } catch (Exception e) {
            // Roll back the batch; rows stay unpublished and are retried next poll.
            throw new IllegalStateException("Failed to publish outbox row " + row.getId(), e);
        }
        row.markPublished();
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
                null, // traceId not captured in the outbox yet
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
