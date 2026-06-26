package com.parkio.media.infrastructure.tracing;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.MDC;

public final class KafkaTraceContextSupport {

    public static final String CORRELATION_ID = "correlationId";
    public static final String EVENT_ID = "eventId";
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";
    public static final String BAGGAGE = "baggage";

    private static final String FIELD_SEPARATOR = "\n";
    private static final String KEY_VALUE_SEPARATOR = "=";
    private static final String CORRELATION_FIELD = "correlationid";
    private static final List<String> PROPAGATION_HEADERS = List.of(TRACEPARENT, TRACESTATE, BAGGAGE);

    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private KafkaTraceContextSupport() {
    }

    public static String currentOutboxTraceContext() {
        String correlationId = MDC.get(CORRELATION_ID);
        Map<String, String> carrier = new LinkedHashMap<>();
        propagator().inject(Context.current(), carrier, SETTER);
        if (!carrier.containsKey(TRACEPARENT)) {
            return blankToNull(correlationId);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            carrier.put(CORRELATION_FIELD, correlationId);
        }
        return encode(carrier);
    }

    public static String correlationId(String storedContext) {
        Map<String, String> carrier = decode(storedContext);
        String correlationId = carrier.get(CORRELATION_FIELD);
        return correlationId == null ? blankToNull(storedContext) : blankToNull(correlationId);
    }

    public static Context extractedContext(String storedContext) {
        return propagator().extract(Context.current(), decode(storedContext), GETTER);
    }

    public static void addPropagationHeaders(List<Header> headers, String storedContext) {
        Map<String, String> carrier = decode(storedContext);
        for (String key : PROPAGATION_HEADERS) {
            String value = carrier.get(key);
            if (value != null && !value.isBlank()) {
                headers.add(header(key, value));
            }
        }
    }

    public static void putCorrelationAndEventMdc(Iterable<Header> headers, String envelopeTraceId) {
        putMdc(CORRELATION_ID, firstNonBlank(headerValue(headers, "traceId"), envelopeTraceId));
        putMdc(EVENT_ID, headerValue(headers, EVENT_ID));
    }

    public static boolean hasTraceparent(Iterable<Header> headers) {
        return headerValue(headers, TRACEPARENT) != null;
    }

    public static void clearMessagingMdc() {
        MDC.remove(CORRELATION_ID);
        MDC.remove(EVENT_ID);
    }

    private static Header header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static TextMapPropagator propagator() {
        return TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance());
    }

    private static Map<String, String> decode(String storedContext) {
        Map<String, String> carrier = new LinkedHashMap<>();
        if (storedContext == null || storedContext.isBlank()) {
            return carrier;
        }
        if (!storedContext.contains(FIELD_SEPARATOR) && !storedContext.startsWith(CORRELATION_FIELD + KEY_VALUE_SEPARATOR)) {
            carrier.put(CORRELATION_FIELD, storedContext);
            return carrier;
        }
        for (String line : storedContext.split(FIELD_SEPARATOR)) {
            int separator = line.indexOf(KEY_VALUE_SEPARATOR);
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1);
            if (!value.isBlank()) {
                carrier.put(key, value);
            }
        }
        return carrier;
    }

    private static String encode(Map<String, String> carrier) {
        StringBuilder encoded = new StringBuilder();
        carrier.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            if (!encoded.isEmpty()) {
                encoded.append(FIELD_SEPARATOR);
            }
            encoded.append(key).append(KEY_VALUE_SEPARATOR).append(value);
        });
        return encoded.isEmpty() ? null : encoded.toString();
    }

    private static String headerValue(Iterable<Header> headers, String key) {
        Header latest = null;
        for (Header header : headers) {
            if (header.key().equalsIgnoreCase(key)) {
                latest = header;
            }
        }
        return latest == null ? null : blankToNull(new String(latest.value(), StandardCharsets.UTF_8));
    }

    private static void putMdc(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? blankToNull(second) : first;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
