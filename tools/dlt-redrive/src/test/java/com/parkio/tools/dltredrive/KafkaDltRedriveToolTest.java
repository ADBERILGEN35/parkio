package com.parkio.tools.dltredrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

class KafkaDltRedriveToolTest {

    @Test
    void redriveRecordPreservesHeadersAndAddsAuditHeaders() {
        KafkaDltRedriveTool.Options options = new KafkaDltRedriveTool.Options(
                "localhost:29092", "parkio.dlt.notification", "parkio.parking.spot",
                "group", "operator", "fixed", 1, 1000, 3, false, true, false);
        ConsumerRecord<byte[], byte[]> source = new ConsumerRecord<>(
                "parkio.dlt.notification", 1, 42L, bytes("key"), bytes("value"));
        source.headers().add(new RecordHeader("eventId", bytes("event-1")));
        source.headers().add(new RecordHeader("traceparent", bytes("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")));
        source.headers().add(new RecordHeader("tracestate", bytes("vendor=value")));
        source.headers().add(new RecordHeader("baggage", bytes("tenant=parkio")));

        var redriven = KafkaDltRedriveTool.redriveRecord(options, source);

        assertThat(redriven.topic()).isEqualTo("parkio.parking.spot");
        assertThat(redriven.key()).isEqualTo(bytes("key"));
        assertThat(redriven.value()).isEqualTo(bytes("value"));
        assertThat(header(redriven.headers(), "eventId")).isEqualTo("event-1");
        assertThat(header(redriven.headers(), "traceparent")).startsWith("00-4bf92f");
        assertThat(header(redriven.headers(), "tracestate")).isEqualTo("vendor=value");
        assertThat(header(redriven.headers(), "baggage")).isEqualTo("tenant=parkio");
        assertThat(header(redriven.headers(), "parkio-redrive-source-topic")).isEqualTo("parkio.dlt.notification");
        assertThat(header(redriven.headers(), "parkio-redrive-source-partition")).isEqualTo("1");
        assertThat(header(redriven.headers(), "parkio-redrive-source-offset")).isEqualTo("42");
        assertThat(header(redriven.headers(), "parkio-redrive-attempt")).isEqualTo("1");
    }

    @Test
    void rejectsRedriveBackToDltTopic() {
        KafkaDltRedriveTool.Options options = KafkaDltRedriveTool.Options.parse(new String[] {
                "--execute",
                "--source-topic", "parkio.dlt.notification",
                "--target-topic", "parkio.dlt.notification",
                "--operator", "operator",
                "--reason", "bad"
        });

        assertThatThrownBy(options::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be a DLT topic");
    }

    @Test
    void rejectsOversizedBatch() {
        KafkaDltRedriveTool.Options options = KafkaDltRedriveTool.Options.parse(new String[] {
                "--source-topic", "parkio.dlt.notification",
                "--target-topic", "parkio.parking.spot",
                "--max-records", "101"
        });

        assertThatThrownBy(options::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--max-records");
    }

    @Test
    void offsetSelectorsNeedAPartition() {
        assertThatThrownBy(() -> validate("--from-offset", "0", "--to-offset", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--partition");
    }

    @Test
    void offsetRangeMustBeOrderedAndComplete() {
        assertThatThrownBy(() -> validate("--partition", "0", "--from-offset", "5", "--to-offset", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--from-offset");
        assertThatThrownBy(() -> validate("--partition", "0", "--from-offset", "5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--to-offset");
    }

    @Test
    void negativeOrNonNumericSelectorsAreRejected() {
        assertThatThrownBy(() -> validate("--partition", "-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate("--partition", "0", "--from-offset", "-1", "--to-offset", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validate("--partition", "zero")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankEventIdIsRejected() {
        assertThatThrownBy(() -> validate("--event-id", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--event-id");
    }

    @Test
    void unknownOptionsAreRejected() {
        assertThatThrownBy(() -> validate("--partitions", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--partitions");
    }

    private static void validate(String... selection) {
        String[] base = {"--source-topic", "parkio.dlt.notification", "--target-topic", "parkio.parking.spot"};
        String[] args = new String[base.length + selection.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(selection, 0, args, base.length, selection.length);
        KafkaDltRedriveTool.Options.parse(args).validate();
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
