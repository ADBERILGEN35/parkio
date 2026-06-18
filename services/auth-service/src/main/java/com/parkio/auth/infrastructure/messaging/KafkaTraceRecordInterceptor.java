package com.parkio.auth.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

/**
 * Propagates the producer's {@code traceId} (Kafka header, falling back to the envelope
 * field) into MDC for the duration of each consumed record (kafka-transport.md).
 */
@Component
public class KafkaTraceRecordInterceptor implements RecordInterceptor<String, String> {

    private final ObjectMapper objectMapper;

    public KafkaTraceRecordInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ConsumerRecord<String, String> intercept(
            ConsumerRecord<String, String> record,
            Consumer<String, String> consumer) {
        String traceId = headerTraceId(record);
        if (traceId == null) {
            traceId = envelopeTraceId(record.value());
        }
        if (traceId == null || traceId.isBlank()) {
            MDC.remove("correlationId");
        } else {
            MDC.put("correlationId", traceId);
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        MDC.remove("correlationId");
    }

    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        MDC.remove("correlationId");
    }

    private static String headerTraceId(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("traceId");
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private String envelopeTraceId(String value) {
        try {
            String traceId = objectMapper.readTree(value).path("traceId").asText(null);
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (Exception ignored) {
            return null;
        }
    }
}
