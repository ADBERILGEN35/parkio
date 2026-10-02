package com.parkio.tools.dltredrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * U07 / CL-F24: targeted DLT replay on a disposable Kafka broker. A DLT with two partitions
 * (partition 0: events e0..e4 at offsets 0..4; partition 1: f0..f2 at offsets 0..2) is replayed
 * with partition/offset/eventId selectors; only the selected records may reach the target,
 * dry-run writes nothing, invalid selections are refused before any write. Synthetic records.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class KafkaDltRedriveToolIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    private String dlt;
    private String target;

    @BeforeEach
    void seedDlt() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        dlt = "parkio.dlt.redrive-it-" + suffix;
        target = "parkio.redrive-it.target-" + suffix;
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(dlt, 2, (short) 1), new NewTopic(target, 1, (short) 1))).all().get();
        }
        try (KafkaProducer<byte[], byte[]> producer = producer()) {
            for (int i = 0; i < 5; i++) {
                producer.send(record(0, "e" + i, i == 2 ? 3 : 0)).get();
            }
            for (int i = 0; i < 3; i++) {
                producer.send(record(1, "f" + i, 0)).get();
            }
        }
    }

    @Test
    void executeRedrivesOnlyTheSelectedOffsetRange() {
        redrive("--execute", "--partition", "0", "--from-offset", "3", "--to-offset", "4");

        assertThat(targetEventIds()).containsExactly("e3", "e4");
        assertThat(targetHeader("parkio-redrive-source-offset")).containsExactly("3", "4");
    }

    @Test
    void executeRedrivesOnlyTheSelectedEventId() {
        redrive("--execute", "--event-id", "f1");

        assertThat(targetEventIds()).containsExactly("f1");
        assertThat(targetHeader("parkio-redrive-source-partition")).containsExactly("1");
    }

    @Test
    void partitionAndEventIdSelectorsCombine() {
        String output = redrive("--execute", "--partition", "0", "--event-id", "f1");

        assertThat(output).contains("action=NO_RECORDS");
        assertThat(targetEventIds()).isEmpty();
    }

    @Test
    void dryRunPreviewsTheSelectionAndWritesNothing() {
        String output = redrive("--partition", "1", "--from-offset", "0", "--to-offset", "2");

        assertThat(output.lines().filter(line -> line.startsWith("action=DRY_RUN"))).hasSize(3);
        assertThat(output).contains("eventId=f0").contains("eventId=f2").doesNotContain("eventId=e0");
        assertThat(targetEventIds()).isEmpty();
    }

    @Test
    void invalidSelectionsAreRefusedBeforeAnyWrite() {
        assertThatThrownBy(() -> redrive("--execute", "--partition", "0", "--from-offset", "3", "--to-offset", "9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> redrive("--execute", "--partition", "0", "--from-offset", "4", "--to-offset", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> redrive("--execute", "--partition", "7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition 7");
        assertThatThrownBy(() -> redrive("--execute", "--from-offset", "0", "--to-offset", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--partition");
        assertThat(targetEventIds()).isEmpty();
    }

    @Test
    void aRecordOverTheRedriveLimitStopsTheBatchBeforeAnyWrite() {
        // e2 already carries parkio-redrive-attempt=3 (the default limit).
        assertThatThrownBy(() -> redrive("--execute", "--partition", "0", "--from-offset", "0", "--to-offset", "4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max redrive attempts");
        assertThat(targetEventIds()).isEmpty();
    }

    @Test
    void replayingTheSameSelectionKeepsTheEventIdForDownstreamDeduplication() {
        redrive("--execute", "--partition", "0", "--from-offset", "1", "--to-offset", "1");
        redrive("--execute", "--partition", "0", "--from-offset", "1", "--to-offset", "1");

        // Consumers deduplicate by eventId; the tool never rewrites it.
        assertThat(targetEventIds()).containsExactly("e1", "e1");
    }

    private String redrive(String... selection) {
        List<String> args = new ArrayList<>(List.of(
                "--bootstrap-servers", KAFKA.getBootstrapServers(),
                "--source-topic", dlt,
                "--target-topic", target,
                "--operator", "redrive-it",
                "--reason", "integration test",
                "--timeout-ms", "15000"));
        args.addAll(List.of(selection));
        KafkaDltRedriveTool.Options options = KafkaDltRedriveTool.Options.parse(args.toArray(String[]::new));
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            options.validate();
            KafkaDltRedriveTool.run(options);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private ProducerRecord<byte[], byte[]> record(int partition, String eventId, int previousRedrives) {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(dlt, partition,
                eventId.getBytes(StandardCharsets.UTF_8), ("{\"eventId\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8));
        record.headers().add(new RecordHeader("eventId", eventId.getBytes(StandardCharsets.UTF_8)));
        if (previousRedrives > 0) {
            record.headers().add(new RecordHeader("parkio-redrive-attempt",
                    Integer.toString(previousRedrives).getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    private List<String> targetEventIds() {
        return targetHeader("eventId");
    }

    private List<String> targetHeader(String name) {
        List<String> values = new ArrayList<>();
        for (ConsumerRecord<byte[], byte[]> record : readTarget()) {
            Header header = record.headers().lastHeader(name);
            values.add(header == null ? null : new String(header.value(), StandardCharsets.UTF_8));
        }
        return values;
    }

    /** Everything in the single target partition, read up to its current end. */
    private List<ConsumerRecord<byte[], byte[]>> readTarget() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "redrive-it-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            TopicPartition partition = new TopicPartition(target, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (consumer.position(partition) < end && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        return records;
    }

    private static KafkaProducer<byte[], byte[]> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(props);
    }
}
