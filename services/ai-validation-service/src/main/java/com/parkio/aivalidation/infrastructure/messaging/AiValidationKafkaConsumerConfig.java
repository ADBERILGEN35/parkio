package com.parkio.aivalidation.infrastructure.messaging;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer wiring for media / parking events. Manual ack after successful handling.
 *
 * <p><b>Poll safety for synchronous vision:</b> worst-case per-record work is roughly
 * media read timeout + Gemini read timeout × (1 + retries) + retry sleep (~35–45s).
 * {@code max.poll.records} defaults to <strong>1</strong> so a slow provider cannot
 * exhaust {@code max.poll.interval.ms} via batching. Interval defaults to 180s (not an
 * arbitrarily huge value) — about 4× the modeled worst case with records=1. Concurrency
 * is fixed at 1 to respect provider quotas; raise only with explicit quota math.
 *
 * <p>Error handling: transient failures retry briefly; poison records go to
 * {@code parkio.dlt.aivalidation}.
 */
@Configuration
public class AiValidationKafkaConsumerConfig {

    public static final String DLT_AIVALIDATION = "parkio.dlt.aivalidation";

    /** Modeled worst-case provider work per record (ms) used by safety math/tests. */
    public static final long MODELED_WORST_CASE_RECORD_MS = 45_000L;

    private final String bootstrapServers;
    private final boolean autoStartup;
    private final int maxPollRecords;
    private final int maxPollIntervalMs;

    public AiValidationKafkaConsumerConfig(
            @Value("${spring.kafka.bootstrap-servers:localhost:29092}") String bootstrapServers,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup,
            @Value("${parkio.kafka.consumer.max-poll-records:${SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS:1}}")
            int maxPollRecords,
            @Value("${parkio.kafka.consumer.max-poll-interval-ms:${SPRING_KAFKA_CONSUMER_MAX_POLL_INTERVAL_MS:180000}}")
            int maxPollIntervalMs) {
        this.bootstrapServers = bootstrapServers;
        this.autoStartup = autoStartup;
        this.maxPollRecords = Math.max(1, maxPollRecords);
        this.maxPollIntervalMs = maxPollIntervalMs;
        if (!isPollBatchSafe(this.maxPollRecords, MODELED_WORST_CASE_RECORD_MS, this.maxPollIntervalMs)) {
            throw new IllegalStateException(
                    "Unsafe Kafka poll config: max.poll.records=" + this.maxPollRecords
                            + " × modeledWorstCaseMs=" + MODELED_WORST_CASE_RECORD_MS
                            + " exceeds max.poll.interval.ms=" + this.maxPollIntervalMs);
        }
    }

    /**
     * Returns the largest batch size that keeps {@code batch × worstCaseMs} under the
     * poll interval (strictly less than interval).
     */
    public static int maxSafePollRecords(long worstCaseRecordMs, long pollIntervalMs) {
        if (worstCaseRecordMs <= 0 || pollIntervalMs <= 0) {
            return 1;
        }
        // Strictly less than interval: floor((interval - 1) / worstCase).
        long safe = (pollIntervalMs - 1) / worstCaseRecordMs;
        return (int) Math.max(1, safe);
    }

    public static boolean isPollBatchSafe(int maxPollRecords, long worstCaseRecordMs, long pollIntervalMs) {
        return (long) maxPollRecords * worstCaseRecordMs < pollIntervalMs;
    }

    @Bean
    ConsumerFactory<String, String> mediaEventsConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> mediaEventsKafkaListenerContainerFactory(
            ConsumerFactory<String, String> mediaEventsConsumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            KafkaTraceRecordInterceptor traceInterceptor) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(mediaEventsConsumerFactory);
        factory.setAutoStartup(autoStartup);
        factory.setConcurrency(1);
        factory.setRecordInterceptor(traceInterceptor);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(DLT_AIVALIDATION, -1));
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L)));
        return factory;
    }
}
