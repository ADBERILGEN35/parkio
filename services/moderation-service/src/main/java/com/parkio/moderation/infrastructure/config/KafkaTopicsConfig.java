package com.parkio.moderation.infrastructure.config;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the Kafka topics this service owns (single-writer ownership) plus its
 * dead-letter topic (one DLT per consuming service). A Spring Boot {@code KafkaAdmin}
 * creates any {@link NewTopic} beans on startup; creation is idempotent. Naming,
 * partitioning and retention follow {@code docs/architecture/kafka-transport.md}. The
 * relay/consumers are not built yet.
 *
 * <p>Replication factor is externalized ({@code parkio.kafka.replication-factor}, default
 * 1 for local/dev). Provisioning can be disabled via {@code parkio.kafka.provision-topics}.
 */
@Configuration
@ConditionalOnProperty(name = "parkio.kafka.provision-topics", havingValue = "true", matchIfMissing = true)
public class KafkaTopicsConfig {

    /** Case lifecycle (opened/resolved, appeals) — keyed by caseId. */
    public static final String MODERATION_CASE = "parkio.moderation.case";
    /** Outward moderator actions (spot rejected, user suspended/restored). */
    public static final String MODERATION_ACTION = "parkio.moderation.action";
    public static final String DLT_MODERATION = "parkio.dlt.moderation";

    private final int replicas;

    public KafkaTopicsConfig(@Value("${parkio.kafka.replication-factor:1}") int replicas) {
        this.replicas = replicas;
    }

    @Bean
    NewTopic moderationCaseTopic() {
        return topic(MODERATION_CASE, 3, Duration.ofDays(30));
    }

    @Bean
    NewTopic moderationActionTopic() {
        return topic(MODERATION_ACTION, 3, Duration.ofDays(30));
    }

    @Bean
    NewTopic moderationDltTopic() {
        return topic(DLT_MODERATION, 3, Duration.ofDays(14));
    }

    private NewTopic topic(String name, int partitions, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicas)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retention.toMillis()))
                .build();
    }
}
