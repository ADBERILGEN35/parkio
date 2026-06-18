package com.parkio.gamification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class KafkaTraceRecordInterceptorTest {

    private final KafkaTraceRecordInterceptor interceptor =
            new KafkaTraceRecordInterceptor(new ObjectMapper());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsHeaderTraceIdIntoMdcAndClearsAfterHandling() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0, "key", "{}");
        record.headers().add(new RecordHeader(
                "traceId", "trace-kafka-header".getBytes(StandardCharsets.UTF_8)));

        interceptor.intercept(record, null);
        assertThat(MDC.get("correlationId")).isEqualTo("trace-kafka-header");

        interceptor.afterRecord(record, null);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void fallsBackToEnvelopeTraceId() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "topic", 0, 0, "key", "{\"traceId\":\"trace-envelope\"}");

        interceptor.intercept(record, null);

        assertThat(MDC.get("correlationId")).isEqualTo("trace-envelope");
    }
}
