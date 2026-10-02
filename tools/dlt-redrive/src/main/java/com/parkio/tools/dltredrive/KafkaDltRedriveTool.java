package com.parkio.tools.dltredrive;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
            List<TopicPartition> partitions = selectedPartitions(consumer, options);
            consumer.assign(partitions);
            // Read only what the DLT held when the run started: up to the end offsets now, or up
            // to --to-offset inclusive.
            Map<TopicPartition, Long> stopAt = new HashMap<>(consumer.endOffsets(partitions));
            if (options.fromOffset() != null) {
                TopicPartition partition = partitions.get(0);
                long first = consumer.beginningOffsets(partitions).get(partition);
                long last = stopAt.get(partition) - 1;
                if (options.fromOffset() < first || options.toOffset() > last) {
                    throw new IllegalArgumentException("offsets " + options.fromOffset() + ".." + options.toOffset()
                            + " are outside " + partition + " ("
                            + (last < first ? "empty" : "offsets " + first + ".." + last) + ")");
                }
                consumer.seek(partition, options.fromOffset());
                stopAt.put(partition, options.toOffset() + 1);
            } else {
                consumer.seekToBeginning(partitions);
            }
            log("SELECTION", selectionLog(options, partitions, stopAt));
            long deadline = System.currentTimeMillis() + options.timeoutMs();
            while (records.size() < options.maxRecords()
                    && !reachedEnd(consumer, partitions, stopAt)
                    && System.currentTimeMillis() < deadline) {
                ConsumerRecords<byte[], byte[]> polled = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<byte[], byte[]> record : polled) {
                    if (!selected(options, record, stopAt)) {
                        continue;
                    }
                    records.add(record);
                    if (records.size() >= options.maxRecords()) {
                        break;
                    }
                }
            }
            if (records.size() < options.maxRecords() && !reachedEnd(consumer, partitions, stopAt)) {
                log("INCOMPLETE", Map.of("sourceTopic", options.sourceTopic(), "timeoutMs", options.timeoutMs()));
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

        // Check every selected record before producing any, so a batch is never half redriven.
        for (ConsumerRecord<byte[], byte[]> record : records) {
            if (redriveAttempts(record.headers()) + 1 > options.maxRedriveAttempts()) {
                throw new IllegalStateException("record " + record.topic() + "-" + record.partition() + "@"
                        + record.offset() + " exceeds max redrive attempts " + options.maxRedriveAttempts()
                        + "; nothing was redriven");
            }
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

    private static List<TopicPartition> selectedPartitions(KafkaConsumer<byte[], byte[]> consumer, Options options) {
        List<TopicPartition> all = partitions(consumer, options.sourceTopic());
        if (options.partition() == null) {
            return all;
        }
        TopicPartition selected = new TopicPartition(options.sourceTopic(), options.partition());
        if (!all.contains(selected)) {
            throw new IllegalArgumentException("--partition " + options.partition() + ": no partition "
                    + options.partition() + " in " + options.sourceTopic() + " (" + all.size() + " partitions)");
        }
        return List.of(selected);
    }

    private static boolean selected(Options options, ConsumerRecord<byte[], byte[]> record,
                                    Map<TopicPartition, Long> stopAt) {
        Long stop = stopAt.get(new TopicPartition(record.topic(), record.partition()));
        if (stop == null || record.offset() >= stop) {
            return false;
        }
        return options.eventId() == null || options.eventId().equals(headerValue(record.headers(), "eventId"));
    }

    private static boolean reachedEnd(KafkaConsumer<byte[], byte[]> consumer, List<TopicPartition> partitions,
                                      Map<TopicPartition, Long> stopAt) {
        return partitions.stream().allMatch(partition -> consumer.position(partition) >= stopAt.get(partition));
    }

    private static Map<String, Object> selectionLog(Options options, List<TopicPartition> partitions,
                                                    Map<TopicPartition, Long> stopAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sourceTopic", options.sourceTopic());
        fields.put("partitions", partitions.stream().map(TopicPartition::partition).toList());
        fields.put("fromOffset", options.fromOffset());
        fields.put("toOffset", options.toOffset());
        fields.put("eventId", options.eventId());
        fields.put("endOffsets", partitions.stream().map(stopAt::get).toList());
        fields.put("maxRecords", options.maxRecords());
        fields.put("mode", options.dryRun() ? "dry-run" : "execute");
        return fields;
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
            boolean help,
            Integer partition,
            Long fromOffset,
            Long toOffset,
            String eventId) {

        private static final Set<String> KNOWN = Set.of(
                "bootstrap-servers", "source-topic", "target-topic", "group-id", "operator", "reason",
                "max-records", "timeout-ms", "max-redrive-attempts",
                "partition", "from-offset", "to-offset", "event-id");

        /** Options without selectors: every partition from its beginning. */
        Options(String bootstrapServers, String sourceTopic, String targetTopic, String groupId, String operator,
                String reason, int maxRecords, int timeoutMs, int maxRedriveAttempts, boolean dryRun,
                boolean execute, boolean help) {
            this(bootstrapServers, sourceTopic, targetTopic, groupId, operator, reason, maxRecords, timeoutMs,
                    maxRedriveAttempts, dryRun, execute, help, null, null, null, null);
        }

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
                        if (!KNOWN.contains(key)) {
                            // A mistyped selector must not silently widen a replay.
                            throw new IllegalArgumentException("Unknown option: --" + key);
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
                    help,
                    optionalNumber(values, "partition", Integer::valueOf),
                    optionalNumber(values, "from-offset", Long::valueOf),
                    optionalNumber(values, "to-offset", Long::valueOf),
                    values.get("event-id"));
        }

        private static <T> T optionalNumber(Map<String, String> values, String key,
                                            java.util.function.Function<String, T> parser) {
            String value = values.get(key);
            if (value == null) {
                return null;
            }
            try {
                return parser.apply(value.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("--" + key + " must be a number");
            }
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
            if (partition != null && partition < 0) {
                throw new IllegalArgumentException("--partition must be >= 0");
            }
            if ((fromOffset != null || toOffset != null) && partition == null) {
                throw new IllegalArgumentException("--from-offset/--to-offset need --partition");
            }
            if (fromOffset != null && toOffset == null) {
                throw new IllegalArgumentException("--from-offset needs --to-offset");
            }
            if (toOffset != null && fromOffset == null) {
                throw new IllegalArgumentException("--to-offset needs --from-offset");
            }
            if (fromOffset != null && (fromOffset < 0 || toOffset < 0)) {
                throw new IllegalArgumentException("--from-offset and --to-offset must be >= 0");
            }
            if (fromOffset != null && fromOffset > toOffset) {
                throw new IllegalArgumentException("--from-offset must not be after --to-offset");
            }
            if (eventId != null && eventId.isBlank()) {
                throw new IllegalArgumentException("--event-id must not be blank");
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

                    Selection (optional; combinable):
                      --partition <n>                       one DLT partition
                      --from-offset <a> --to-offset <b>     inclusive range in that partition
                      --event-id <id>                       records whose eventId header matches

                    Safety:
                      dry-run is default; --execute requires --reason and operator identity.
                      target topic cannot be a DLT topic; batch size is capped at 100.
                      unknown options and out-of-range offsets are refused before any write;
                      only records present when the run starts are read; if any selected record
                      is over --max-redrive-attempts, nothing is redriven.
                    """);
        }

        private static void require(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
        }
    }
}
