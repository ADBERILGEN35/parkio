package com.parkio.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.platform.tracing.KafkaTraceContextSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

@Component
public class KafkaTraceRecordInterceptor implements RecordInterceptor<String, String> {

    private final ObjectMapper objectMapper;
    private final Counter missingTraceContextCounter;

    public KafkaTraceRecordInterceptor(ObjectMapper objectMapper, MeterRegistry registry) {
        this.objectMapper = objectMapper;
        this.missingTraceContextCounter = Counter.builder("parkio.kafka.trace.propagation.missing")
                .description("Kafka records consumed without W3C traceparent propagation headers")
                .register(registry);
    }

    @Override
    public ConsumerRecord<String, String> intercept(
            ConsumerRecord<String, String> record,
            Consumer<String, String> consumer) {
        if (!KafkaTraceContextSupport.hasTraceparent(record.headers())) {
            missingTraceContextCounter.increment();
        }
        KafkaTraceContextSupport.putCorrelationAndEventMdc(record.headers(), envelopeTraceId(record.value()));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        KafkaTraceContextSupport.clearMessagingMdc();
    }

    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        KafkaTraceContextSupport.clearMessagingMdc();
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
