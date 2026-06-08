package com.parkio.analytics.infrastructure.config;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * analytics-service is a projection-only consumer: it produces no domain topic, so it
 * owns only its dead-letter topic (one DLT per consuming service). A Spring Boot
 * {@code KafkaAdmin} creates the {@link NewTopic} bean on startup; creation is
 * idempotent. Naming/retention follow {@code docs/architecture/kafka-transport.md}.
 * The relay/consumers are not built yet.
 *
 * <p>Replication factor is externalized ({@code parkio.kafka.replication-factor}, default
 * 1 for local/dev). Provisioning can be disabled via {@code parkio.kafka.provision-topics}.
 */
@Configuration
@ConditionalOnProperty(name = "parkio.kafka.provision-topics", havingValue = "true", matchIfMissing = true)
public class KafkaTopicsConfig {

    public static final String DLT_ANALYTICS = "parkio.dlt.analytics";

    private final int replicas;

    public KafkaTopicsConfig(@Value("${parkio.kafka.replication-factor:1}") int replicas) {
        this.replicas = replicas;
    }

    @Bean
    NewTopic analyticsDltTopic() {
        return topic(DLT_ANALYTICS, 3, Duration.ofDays(14));
    }

    private NewTopic topic(String name, int partitions, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicas)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retention.toMillis()))
                .build();
    }
}
