package com.parkio.tools.dltredrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

class KafkaDltRedriveToolTest {

    private static final String TARGET = "parkio.parking.spot";
    private static final String DLT = "parkio.dlt.notification";

    @Test
    void redriveRecordPreservesHeadersAndAddsAuditHeaders() {
        KafkaDltRedriveTool.Options options = options();
        ConsumerRecord<byte[], byte[]> source = record(1, 42L, TARGET);
        source.headers().add(new RecordHeader("eventId", bytes("event-1")));
        source.headers().add(new RecordHeader("traceparent", bytes("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")));
        source.headers().add(new RecordHeader("tracestate", bytes("vendor=value")));
        source.headers().add(new RecordHeader("baggage", bytes("tenant=parkio")));

        var redriven = KafkaDltRedriveTool.redriveRecord(options, source);

        assertThat(redriven.topic()).isEqualTo(TARGET);
        assertThat(redriven.key()).isEqualTo(bytes("key"));
        assertThat(redriven.value()).isEqualTo(bytes("value"));
        assertThat(header(redriven.headers(), "eventId")).isEqualTo("event-1");
        assertThat(header(redriven.headers(), "traceparent")).startsWith("00-4bf92f");
        assertThat(header(redriven.headers(), "tracestate")).isEqualTo("vendor=value");
        assertThat(header(redriven.headers(), "baggage")).isEqualTo("tenant=parkio");
        assertThat(header(redriven.headers(), KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER)).isEqualTo(TARGET);
        assertThat(header(redriven.headers(), "parkio-redrive-source-topic")).isEqualTo(DLT);
        assertThat(header(redriven.headers(), "parkio-redrive-source-partition")).isEqualTo("1");
        assertThat(header(redriven.headers(), "parkio-redrive-source-offset")).isEqualTo("42");
        assertThat(header(redriven.headers(), "parkio-redrive-attempt")).isEqualTo("1");
    }

    @Test
    void matchingBatchPassesPreflightAndRetainsPayloadHeadersAndTarget() {
        KafkaDltRedriveTool.Options options = options();
        ConsumerRecord<byte[], byte[]> matching = record(0, 10L, TARGET);
        matching.headers().add(new RecordHeader("eventId", bytes("match-1")));
        matching.headers().add(new RecordHeader(KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER, bytes(TARGET)));

        KafkaDltRedriveTool.requireBatchReadyToRedrive(List.of(matching), options);

        var redriven = KafkaDltRedriveTool.redriveRecord(options, matching);
        assertThat(redriven.topic()).isEqualTo(TARGET);
        assertThat(redriven.key()).isEqualTo(bytes("key"));
        assertThat(redriven.value()).isEqualTo(bytes("value"));
        assertThat(header(redriven.headers(), "eventId")).isEqualTo("match-1");
        assertThat(header(redriven.headers(), KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER)).isEqualTo(TARGET);
    }

    @Test
    void mixedMismatchBatchRefusesEntireSelectionWithZeroSends() {
        KafkaDltRedriveTool.Options options = options();
        ConsumerRecord<byte[], byte[]> ok = record(0, 1L, TARGET);
        ConsumerRecord<byte[], byte[]> bad = record(0, 2L, "parkio.other.topic");
        AtomicInteger sends = new AtomicInteger();

        assertThatThrownBy(() -> {
            KafkaDltRedriveTool.requireBatchReadyToRedrive(List.of(ok, bad), options);
            sends.incrementAndGet();
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER)
                .hasMessageContaining("does not match --target-topic")
                .hasMessageContaining("nothing was redriven");
        assertThat(sends.get()).isZero();
    }

    @Test
    void absentOriginalTopicHeaderFailsClosed() {
        ConsumerRecord<byte[], byte[]> missing = new ConsumerRecord<>(DLT, 0, 3L, bytes("key"), bytes("value"));

        assertThatThrownBy(() -> KafkaDltRedriveTool.requireOriginalTopicMatchesTarget(missing, TARGET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing " + KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER)
                .hasMessageContaining("nothing was redriven");
    }

    @Test
    void duplicateConflictingOriginalTopicHeadersFailClosed() {
        ConsumerRecord<byte[], byte[]> conflicting = new ConsumerRecord<>(DLT, 0, 4L, bytes("key"), bytes("value"));
        conflicting.headers().add(new RecordHeader(KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER, bytes(TARGET)));
        conflicting.headers().add(new RecordHeader(KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER, bytes("parkio.other.topic")));

        assertThatThrownBy(() -> KafkaDltRedriveTool.requireOriginalTopicMatchesTarget(conflicting, TARGET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting " + KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER)
                .hasMessageContaining("nothing was redriven");
    }

    @Test
    void blankOriginalTopicHeaderFailsClosed() {
        ConsumerRecord<byte[], byte[]> blank = new ConsumerRecord<>(DLT, 0, 5L, bytes("key"), bytes("value"));
        blank.headers().add(new RecordHeader(KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER, bytes("  ")));

        assertThatThrownBy(() -> KafkaDltRedriveTool.requireOriginalTopicMatchesTarget(blank, TARGET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank " + KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER)
                .hasMessageContaining("nothing was redriven");
    }

    @Test
    void rejectsRedriveBackToDltTopic() {
        KafkaDltRedriveTool.Options options = KafkaDltRedriveTool.Options.parse(new String[] {
                "--execute",
                "--source-topic", DLT,
                "--target-topic", DLT,
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
                "--source-topic", DLT,
                "--target-topic", TARGET,
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

    private static KafkaDltRedriveTool.Options options() {
        return new KafkaDltRedriveTool.Options(
                "localhost:29092", DLT, TARGET,
                "group", "operator", "fixed", 1, 1000, 3, false, true, false);
    }

    private static ConsumerRecord<byte[], byte[]> record(int partition, long offset, String originalTopic) {
        ConsumerRecord<byte[], byte[]> source = new ConsumerRecord<>(DLT, partition, offset, bytes("key"), bytes("value"));
        source.headers().add(new RecordHeader(KafkaDltRedriveTool.ORIGINAL_TOPIC_HEADER, bytes(originalTopic)));
        return source;
    }

    private static void validate(String... selection) {
        String[] base = {"--source-topic", DLT, "--target-topic", TARGET};
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
