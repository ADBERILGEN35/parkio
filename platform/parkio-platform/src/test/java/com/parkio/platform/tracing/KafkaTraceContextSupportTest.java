package com.parkio.platform.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.platform.http.PlatformHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class KafkaTraceContextSupportTest {

    @Test
    void preservesW3cKafkaHeaderNamesFromStoredContext() {
        List<Header> headers = new ArrayList<>();
        String storedContext = """
                traceparent=00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
                tracestate=vendor=value
                baggage=tenant=parkio
                correlationid=corr-123
                """;

        KafkaTraceContextSupport.addPropagationHeaders(headers, storedContext);

        assertThat(header(headers, PlatformHeaders.TRACEPARENT)).startsWith("00-4bf92f");
        assertThat(header(headers, PlatformHeaders.TRACESTATE)).isEqualTo("vendor=value");
        assertThat(header(headers, PlatformHeaders.BAGGAGE)).isEqualTo("tenant=parkio");
        assertThat(header(headers, "correlationid")).isNull();
    }

    @Test
    void putsCorrelationAndEventIntoMdcWithoutChangingKeys() {
        List<Header> headers = List.of(
                new RecordHeader(PlatformHeaders.KAFKA_LEGACY_TRACE_ID, bytes("corr-123")),
                new RecordHeader(PlatformHeaders.KAFKA_EVENT_ID, bytes("event-1")));

        try {
            KafkaTraceContextSupport.putCorrelationAndEventMdc(headers, "fallback");

            assertThat(MDC.get(PlatformHeaders.MDC_CORRELATION_ID)).isEqualTo("corr-123");
            assertThat(MDC.get(PlatformHeaders.MDC_EVENT_ID)).isEqualTo("event-1");
        } finally {
            KafkaTraceContextSupport.clearMessagingMdc();
        }
    }

    @Test
    void detectsTraceparentCaseInsensitively() {
        List<Header> headers = List.of(new RecordHeader("TraceParent", bytes("value")));

        assertThat(KafkaTraceContextSupport.hasTraceparent(headers)).isTrue();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String header(Iterable<Header> headers, String key) {
        Header latest = null;
        for (Header header : headers) {
            if (header.key().equals(key)) {
                latest = header;
            }
        }
        return latest == null ? null : new String(latest.value(), StandardCharsets.UTF_8);
    }
}
