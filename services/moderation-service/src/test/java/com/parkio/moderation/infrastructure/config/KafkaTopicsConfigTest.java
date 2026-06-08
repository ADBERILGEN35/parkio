package com.parkio.moderation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

/** Verifies this service's Kafka topic provisioning (name, partitions, replicas, retention). */
class KafkaTopicsConfigTest {

    private final KafkaTopicsConfig config = new KafkaTopicsConfig(1);

    @Test
    void provisionsModerationCaseTopic30Day() {
        NewTopic t = config.moderationCaseTopic();
        assertThat(t.name()).isEqualTo("parkio.moderation.case");
        assertThat(t.numPartitions()).isEqualTo(3);
        assertThat(t.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "2592000000");
    }

    @Test
    void provisionsModerationActionTopic30Day() {
        NewTopic t = config.moderationActionTopic();
        assertThat(t.name()).isEqualTo("parkio.moderation.action");
        assertThat(t.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "2592000000");
    }

    @Test
    void provisionsModerationDltTopic() {
        NewTopic t = config.moderationDltTopic();
        assertThat(t.name()).isEqualTo("parkio.dlt.moderation");
        assertThat(t.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "1209600000");
    }
}
