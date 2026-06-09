package com.parkio.moderation.infrastructure.messaging;

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
 * Kafka consumer wiring shared by moderation's listeners (media + ai-validation topics,
 * both group {@code parkio.moderation}). The envelope is read as a raw JSON string
 * (deserialized by each listener), so a {@code StringDeserializer} is used here. Manual
 * ack: the offset is committed only after a successful transaction.
 *
 * <p>Error handling (kafka-transport.md): transient failures are retried with a short
 * backoff; exhausted/poison records are published to {@code parkio.dlt.moderation} so a
 * single bad message never blocks the partition.
 */
@Configuration
public class ModerationKafkaConsumerConfig {

    public static final String DLT_MODERATION = "parkio.dlt.moderation";

    private final String bootstrapServers;
    private final boolean autoStartup;

    public ModerationKafkaConsumerConfig(
            @Value("${spring.kafka.bootstrap-servers:localhost:29092}") String bootstrapServers,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
        this.bootstrapServers = bootstrapServers;
        this.autoStartup = autoStartup;
    }

    @Bean
    ConsumerFactory<String, String> moderationConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> moderationKafkaListenerContainerFactory(
            ConsumerFactory<String, String> moderationConsumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            KafkaTraceRecordInterceptor traceInterceptor) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(moderationConsumerFactory);
        factory.setAutoStartup(autoStartup);
        factory.setRecordInterceptor(traceInterceptor);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(DLT_MODERATION, -1));
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L)));
        return factory;
    }
}
