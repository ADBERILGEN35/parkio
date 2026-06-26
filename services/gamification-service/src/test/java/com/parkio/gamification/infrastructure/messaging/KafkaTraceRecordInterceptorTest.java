package com.parkio.gamification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class KafkaTraceRecordInterceptorTest {

    private final KafkaTraceRecordInterceptor interceptor =
            new KafkaTraceRecordInterceptor(new ObjectMapper(), new SimpleMeterRegistry());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsHeaderTraceIdIntoMdcAndClearsAfterHandling() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0, "key", "{}");
        record.headers().add(new RecordHeader(
                "traceId", "trace-kafka-header".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(
                "eventId", "event-123".getBytes(StandardCharsets.UTF_8)));

        interceptor.intercept(record, null);
        assertThat(MDC.get("correlationId")).isEqualTo("trace-kafka-header");
        assertThat(MDC.get("eventId")).isEqualTo("event-123");

        interceptor.afterRecord(record, null);
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("eventId")).isNull();
    }

    @Test
    void fallsBackToEnvelopeTraceId() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "topic", 0, 0, "key", "{\"traceId\":\"trace-envelope\"}");

        interceptor.intercept(record, null);

        assertThat(MDC.get("correlationId")).isEqualTo("trace-envelope");
    }

    @Test
    void countsRecordsWithoutW3cTraceContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaTraceRecordInterceptor localInterceptor = new KafkaTraceRecordInterceptor(new ObjectMapper(), registry);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0, "key", "{}");

        localInterceptor.intercept(record, null);

        assertThat(registry.counter("parkio.kafka.trace.propagation.missing").count()).isEqualTo(1.0);
    }
}
