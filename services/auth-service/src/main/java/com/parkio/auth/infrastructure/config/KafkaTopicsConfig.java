package com.parkio.auth.infrastructure.config;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the Kafka topics this service owns (single-writer ownership). A Spring Boot
 * {@code KafkaAdmin} creates any {@link NewTopic} beans on startup; creation is
 * idempotent. Topic naming, partitioning and retention follow
 * {@code docs/architecture/kafka-transport.md}. The relay/consumers are not built yet.
 *
 * <p>Replication factor is externalized ({@code parkio.kafka.replication-factor}, default
 * 1 for local/dev). Provisioning can be disabled via {@code parkio.kafka.provision-topics}.
 */
@Configuration
@ConditionalOnProperty(name = "parkio.kafka.provision-topics", havingValue = "true", matchIfMissing = true)
public class KafkaTopicsConfig {

    public static final String AUTH_USER = "parkio.auth.user";

    private final int replicas;

    public KafkaTopicsConfig(@Value("${parkio.kafka.replication-factor:1}") int replicas) {
        this.replicas = replicas;
    }

    @Bean
    NewTopic authUserTopic() {
        return topic(AUTH_USER, 3, Duration.ofDays(7));
    }

    private NewTopic topic(String name, int partitions, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicas)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retention.toMillis()))
                .build();
    }
}
