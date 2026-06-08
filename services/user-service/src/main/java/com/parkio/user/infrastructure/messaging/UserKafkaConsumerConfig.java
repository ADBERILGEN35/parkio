package com.parkio.user.infrastructure.messaging;

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
 * Kafka consumer wiring for the events user-service subscribes to. The envelope is read
 * as a raw JSON string (deserialized by the listener), so a {@code StringDeserializer}
 * is used here. Manual ack: the offset is committed only after a successful transaction.
 *
 * <p>Error handling (kafka-transport.md): transient failures are retried with a short
 * backoff; exhausted/poison records are published to {@code parkio.dlt.user} so a single
 * bad message never blocks the partition.
 */
@Configuration
public class UserKafkaConsumerConfig {

    public static final String DLT_USER = "parkio.dlt.user";

    private final String bootstrapServers;
    private final boolean autoStartup;

    public UserKafkaConsumerConfig(
            @Value("${spring.kafka.bootstrap-servers:localhost:29092}") String bootstrapServers,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
        this.bootstrapServers = bootstrapServers;
        this.autoStartup = autoStartup;
    }

    @Bean
    ConsumerFactory<String, String> authUserConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> authUserKafkaListenerContainerFactory(
            ConsumerFactory<String, String> authUserConsumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(authUserConsumerFactory);
        factory.setAutoStartup(autoStartup);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);

        // Retry transient failures a few times, then route poison messages to the DLT
        // (partition -1 lets the broker choose). Never blocks the partition forever.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(DLT_USER, -1));
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L)));
        return factory;
    }
}
