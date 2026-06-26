package com.parkio.tools.dltredrive;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

public final class KafkaDltRedriveTool {

    private static final int MAX_BATCH_SIZE = 100;
    private static final int DEFAULT_MAX_RECORDS = 10;
    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_MAX_REDRIVE_ATTEMPTS = 3;

    private KafkaDltRedriveTool() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        if (options.help()) {
            Options.printUsage();
            return;
        }
        options.validate();
        run(options);
    }

    static void run(Options options) {
        Properties consumerProps = consumerProps(options);
        Properties producerProps = producerProps(options);
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps)) {
            List<TopicPartition> partitions = partitions(consumer, options.sourceTopic());
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.currentTimeMillis() + options.timeoutMs();
            while (records.size() < options.maxRecords() && System.currentTimeMillis() < deadline) {
                ConsumerRecords<byte[], byte[]> polled = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<byte[], byte[]> record : polled) {
                    records.add(record);
                    if (records.size() >= options.maxRecords()) {
                        break;
                    }
                }
            }
        }

        if (records.isEmpty()) {
            log("NO_RECORDS", Map.of("sourceTopic", options.sourceTopic()));
            return;
        }

        if (options.dryRun()) {
            for (ConsumerRecord<byte[], byte[]> record : records) {
                log("DRY_RUN", recordLog(options, record));
            }
            return;
        }

        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(producerProps)) {
            for (ConsumerRecord<byte[], byte[]> record : records) {
                ProducerRecord<byte[], byte[]> redrive = redriveRecord(options, record);
                try {
                    producer.send(redrive).get();
                } catch (Exception ex) {
                    throw new IllegalStateException("Failed to redrive " + record.topic() + "-"
                            + record.partition() + "@" + record.offset(), ex);
                }
                log("REDRIVEN", recordLog(options, record));
            }
            producer.flush();
        }
    }

    static ProducerRecord<byte[], byte[]> redriveRecord(Options options, ConsumerRecord<byte[], byte[]> source) {
        Headers headers = copyHeaders(source.headers());
        int attempts = redriveAttempts(headers) + 1;
        if (attempts > options.maxRedriveAttempts()) {
            throw new IllegalStateException("record " + source.topic() + "-" + source.partition() + "@"
                    + source.offset() + " exceeds max redrive attempts " + options.maxRedriveAttempts());
        }
        headers.remove("parkio-redrive-source-topic");
        headers.remove("parkio-redrive-source-partition");
        headers.remove("parkio-redrive-source-offset");
        headers.remove("parkio-redrive-attempt");
        headers.remove("parkio-redrive-operator");
        headers.remove("parkio-redrive-reason");
        headers.add(header("parkio-redrive-source-topic", source.topic()));
        headers.add(header("parkio-redrive-source-partition", Integer.toString(source.partition())));
        headers.add(header("parkio-redrive-source-offset", Long.toString(source.offset())));
        headers.add(header("parkio-redrive-attempt", Integer.toString(attempts)));
        headers.add(header("parkio-redrive-operator", options.operator()));
        headers.add(header("parkio-redrive-reason", options.reason()));
        Long timestamp = source.timestamp() < 0 ? null : source.timestamp();
        return new ProducerRecord<>(
                options.targetTopic(), null, timestamp, source.key(), source.value(), headers);
    }

    static Headers copyHeaders(Headers source) {
        Headers copy = new org.apache.kafka.common.header.internals.RecordHeaders();
        for (Header header : source) {
            byte[] value = header.value() == null ? null : header.value().clone();
            copy.add(new RecordHeader(header.key(), value));
        }
        return copy;
    }

    static int redriveAttempts(Headers headers) {
        Header header = headers.lastHeader("parkio-redrive-attempt");
        if (header == null || header.value() == null) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Header header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static List<TopicPartition> partitions(KafkaConsumer<byte[], byte[]> consumer, String topic) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(10));
        if (infos == null || infos.isEmpty()) {
            throw new IllegalStateException("No partitions found for source topic " + topic);
        }
        return infos.stream().map(info -> new TopicPartition(topic, info.partition())).toList();
    }

    private static Properties consumerProps(Options options) {
        Properties props = new Properties();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, options.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, options.groupId());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return props;
    }

    private static Properties producerProps(Options options) {
        Properties props = new Properties();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, options.bootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return props;
    }

    private static Map<String, Object> recordLog(Options options, ConsumerRecord<byte[], byte[]> record) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceTopic", record.topic());
        fields.put("sourcePartition", record.partition());
        fields.put("sourceOffset", record.offset());
        fields.put("targetTopic", options.targetTopic());
        fields.put("operator", options.operator());
        fields.put("eventId", headerValue(record.headers(), "eventId"));
        fields.put("traceparent", headerValue(record.headers(), "traceparent"));
        fields.put("correlationId", headerValue(record.headers(), "traceId"));
        fields.put("valueBytes", record.value() == null ? 0 : record.value().length);
        return fields;
    }

    private static String headerValue(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static void log(String action, Map<String, Object> fields) {
        StringBuilder line = new StringBuilder("action=").append(action);
        fields.forEach((key, value) -> line.append(' ').append(key).append('=').append(value));
        System.out.println(line);
    }

    record Options(
            String bootstrapServers,
            String sourceTopic,
            String targetTopic,
            String groupId,
            String operator,
            String reason,
            int maxRecords,
            int timeoutMs,
            int maxRedriveAttempts,
            boolean dryRun,
            boolean execute,
            boolean help) {

        static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            boolean dryRun = true;
            boolean execute = false;
            boolean help = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--dry-run" -> dryRun = true;
                    case "--execute" -> {
                        execute = true;
                        dryRun = false;
                    }
                    case "-h", "--help" -> help = true;
                    default -> {
                        if (!arg.startsWith("--")) {
                            throw new IllegalArgumentException("Unknown argument: " + arg);
                        }
                        String key;
                        String value;
                        int equals = arg.indexOf('=');
                        if (equals > 0) {
                            key = arg.substring(2, equals);
                            value = arg.substring(equals + 1);
                        } else {
                            key = arg.substring(2);
                            if (i + 1 >= args.length) {
                                throw new IllegalArgumentException("Missing value for " + arg);
                            }
                            value = args[++i];
                        }
                        values.put(key, value);
                    }
                }
            }
            return new Options(
                    values.getOrDefault("bootstrap-servers", "localhost:29092"),
                    values.get("source-topic"),
                    values.get("target-topic"),
                    values.getOrDefault("group-id", "parkio-dlt-redrive-" + System.currentTimeMillis()),
                    values.getOrDefault("operator", System.getenv().getOrDefault("PARKIO_OPERATOR", "unknown")),
                    values.getOrDefault("reason", ""),
                    Integer.parseInt(values.getOrDefault("max-records", Integer.toString(DEFAULT_MAX_RECORDS))),
                    Integer.parseInt(values.getOrDefault("timeout-ms", Integer.toString(DEFAULT_TIMEOUT_MS))),
                    Integer.parseInt(values.getOrDefault(
                            "max-redrive-attempts", Integer.toString(DEFAULT_MAX_REDRIVE_ATTEMPTS))),
                    dryRun,
                    execute,
                    help);
        }

        void validate() {
            if (help) {
                return;
            }
            require(sourceTopic, "--source-topic");
            require(targetTopic, "--target-topic");
            if (!sourceTopic.startsWith("parkio.dlt.")) {
                throw new IllegalArgumentException("--source-topic must be a parkio.dlt.* topic");
            }
            if (targetTopic.startsWith("parkio.dlt.")) {
                throw new IllegalArgumentException("--target-topic must not be a DLT topic");
            }
            if (sourceTopic.equals(targetTopic)) {
                throw new IllegalArgumentException("source and target topics must differ");
            }
            if (maxRecords < 1 || maxRecords > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("--max-records must be 1.." + MAX_BATCH_SIZE);
            }
            if (execute && reason.isBlank()) {
                throw new IllegalArgumentException("--execute requires --reason");
            }
            if (execute && "unknown".equals(operator)) {
                throw new IllegalArgumentException("--execute requires --operator or PARKIO_OPERATOR");
            }
        }

        static void printUsage() {
            System.out.println("""
                    Kafka DLT redrive tool

                    Dry-run:
                      ./gradlew :tools:dlt-redrive:run --args='--source-topic parkio.dlt.notification --target-topic parkio.parking.spot --max-records 10'

                    Execute:
                      PARKIO_OPERATOR=alice ./gradlew :tools:dlt-redrive:run --args='--execute --source-topic parkio.dlt.notification --target-topic parkio.parking.spot --reason "consumer fixed"'

                    Required:
                      --source-topic parkio.dlt.<service>
                      --target-topic <original-topic>

                    Safety:
                      dry-run is default; --execute requires --reason and operator identity.
                      target topic cannot be a DLT topic; batch size is capped at 100.
                    """);
        }

        private static void require(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
        }
    }
}
