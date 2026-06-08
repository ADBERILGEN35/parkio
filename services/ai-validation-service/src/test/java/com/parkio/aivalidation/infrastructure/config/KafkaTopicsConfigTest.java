package com.parkio.aivalidation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

/** Verifies this service's Kafka topic provisioning (name, partitions, replicas, retention). */
class KafkaTopicsConfigTest {

    private final KafkaTopicsConfig config = new KafkaTopicsConfig(1);

    @Test
    void provisionsAiValidationResultTopicAsHot7Day() {
        NewTopic t = config.aiValidationResultTopic();
        assertThat(t.name()).isEqualTo("parkio.aivalidation.result");
        assertThat(t.numPartitions()).isEqualTo(6);
        assertThat(t.replicationFactor()).isEqualTo((short) 1);
        assertThat(t.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "604800000");
    }

    @Test
    void provisionsAiValidationDltTopic() {
        NewTopic t = config.aiValidationDltTopic();
        assertThat(t.name()).isEqualTo("parkio.dlt.aivalidation");
        assertThat(t.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "1209600000");
    }
}
